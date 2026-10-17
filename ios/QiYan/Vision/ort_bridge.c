#include "ort_bridge.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <onnxruntime/onnxruntime_c_api.h>

struct QYVision {
    const OrtApi *api;
    OrtEnv *env;
    OrtSession *pose;
    OrtSession *layout;
    OrtMemoryInfo *memory;
};

static int failed(const OrtApi *api, OrtStatus *status, char *err, int err_len) {
    if (!status) return 0;
    if (err && err_len > 0) snprintf(err, (size_t)err_len, "%s", api->GetErrorMessage(status));
    api->ReleaseStatus(status);
    return 1;
}

QYVision *qy_vision_create(const char *pose_path, const char *layout_path, char *err, int err_len) {
    const OrtApi *api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (!api) return NULL;
    QYVision *v = calloc(1, sizeof(QYVision));
    v->api = api;
    OrtSessionOptions *opts = NULL;
    if (failed(api, api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "qiyan", &v->env), err, err_len) ||
        failed(api, api->CreateSessionOptions(&opts), err, err_len) ||
        failed(api, api->SetIntraOpNumThreads(opts, 2), err, err_len) ||
        failed(api, api->SetSessionGraphOptimizationLevel(opts, ORT_ENABLE_ALL), err, err_len) ||
        failed(api, api->CreateSession(v->env, pose_path, opts, &v->pose), err, err_len) ||
        failed(api, api->CreateSession(v->env, layout_path, opts, &v->layout), err, err_len) ||
        failed(api, api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &v->memory), err, err_len)) {
        if (opts) api->ReleaseSessionOptions(opts);
        qy_vision_free(v);
        return NULL;
    }
    api->ReleaseSessionOptions(opts);
    return v;
}

void qy_vision_free(QYVision *v) {
    if (!v) return;
    if (v->memory) v->api->ReleaseMemoryInfo(v->memory);
    if (v->pose) v->api->ReleaseSession(v->pose);
    if (v->layout) v->api->ReleaseSession(v->layout);
    if (v->env) v->api->ReleaseEnv(v->env);
    free(v);
}

/// Runs one model with a single NCHW float input and copies `count` floats out of each output.
/// Input/output names come from the session (outputs by index, like the Android port).
static int run(QYVision *v, OrtSession *session, const float *input, int width, int height,
               int outputs, float *const *dst, const size_t *count) {
    const OrtApi *api = v->api;
    OrtAllocator *alloc = NULL;
    if (failed(api, api->GetAllocatorWithDefaultOptions(&alloc), NULL, 0)) return -1;
    char *in_name = NULL;
    char *out_names[2] = {NULL, NULL};
    int rc = 0;
    if (failed(api, api->SessionGetInputName(session, 0, alloc, &in_name), NULL, 0)) return -1;
    for (int i = 0; i < outputs; i++)
        if (failed(api, api->SessionGetOutputName(session, (size_t)i, alloc, &out_names[i]), NULL, 0)) rc = -1;

    int64_t shape[4] = {1, 3, height, width};
    OrtValue *tensor = NULL;
    OrtValue *out[2] = {NULL, NULL};
    if (rc == 0 && failed(api, api->CreateTensorWithDataAsOrtValue(v->memory, (void *)input,
                                                                    sizeof(float) * 3 * (size_t)width * (size_t)height, shape, 4,
                                                                    ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensor), NULL, 0))
        rc = -1;
    if (rc == 0) {
        const char *in_names[] = {in_name};
        if (failed(api, api->Run(session, NULL, in_names, (const OrtValue *const *)&tensor, 1,
                                 (const char *const *)out_names, (size_t)outputs, out), NULL, 0)) {
            rc = -1;
        } else {
            for (int i = 0; i < outputs; i++) {
                float *data = NULL;
                if (failed(api, api->GetTensorMutableData(out[i], (void **)&data), NULL, 0) || !data) { rc = -1; break; }
                memcpy(dst[i], data, sizeof(float) * count[i]);
            }
        }
    }
    for (int i = 0; i < outputs; i++) if (out[i]) api->ReleaseValue(out[i]);
    if (tensor) api->ReleaseValue(tensor);
    for (int i = 0; i < outputs; i++) if (out_names[i]) alloc->Free(alloc, out_names[i]);
    if (in_name) alloc->Free(alloc, in_name);
    return rc;
}

int qy_vision_pose(QYVision *v, const float *input, float *simcc_x, float *simcc_y) {
    float *dst[] = {simcc_x, simcc_y};
    size_t count[] = {4 * 512, 4 * 512};
    return run(v, v->pose, input, 256, 256, 2, dst, count);
}

int qy_vision_layout(QYVision *v, const float *input, int width, int height, float *probs) {
    float *dst[] = {probs};
    size_t count[] = {90 * 16};
    return run(v, v->layout, input, width, height, 1, dst, count);
}
