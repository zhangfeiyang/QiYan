// Thin C wrapper over the ONNX Runtime C API for the two board-recognition models
// (RTMPose corners + SwinV2 piece layout, see the Android BoardRecognizer).
#ifndef ORT_BRIDGE_H
#define ORT_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct QYVision QYVision;

/// Loads both models; NULL on failure (err gets a message).
QYVision *qy_vision_create(const char *pose_path, const char *layout_path, char *err, int err_len);
void qy_vision_free(QYVision *v);

/// input: NCHW float 1x3x256x256. Writes simcc_x / simcc_y, each 4x512. Returns 0 on success.
int qy_vision_pose(QYVision *v, const float *input, float *simcc_x, float *simcc_y);

/// input: NCHW float 1x3xHxW. Writes 90x16 class probabilities (already softmaxed). Returns 0 on success.
int qy_vision_layout(QYVision *v, const float *input, int width, int height, float *probs);

#ifdef __cplusplus
}
#endif

#endif
