package com.zhongguoxiangqi.ui.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 语音指挥下棋的识别入口.
 *
 * 封装 Android [SpeechRecognizer]: 把麦克风收进来的中文语音转成文本,
 * 交给 [onResult] 回调; 失败时把 [SpeechRecognizer] 的错误码交给 [onError].
 *
 * 识别用的是系统语音服务(Google / 厂商自带), 不依赖任何第三方 SDK.
 * 需要 RECORD_AUDIO 运行时权限, 由调用方在点按麦克风时申请.
 */
class VoiceInputController(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (Int) -> Unit,
) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }

    /** 设备是否支持语音识别 */
    val available: Boolean get() = recognizer != null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            onError(error)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (text != null && text.isNotBlank()) {
                onResult(text)
            } else {
                onError(SpeechRecognizer.ERROR_NO_MATCH)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    init {
        recognizer?.setRecognitionListener(listener)
    }

    /** 开始听一段话. 识别完成或出错后自动结束, 可再次调用. */
    fun start() {
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        runCatching {
            r.cancel()
            r.startListening(intent)
        }
    }

    /** 用户中途取消(点别处/退出界面时调用) */
    fun cancel() {
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
    }
}
