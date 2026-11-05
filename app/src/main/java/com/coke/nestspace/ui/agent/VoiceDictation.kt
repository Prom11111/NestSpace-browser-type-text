package com.coke.nestspace.ui.agent

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.coke.nestspace.assistant.FloatingDotSettings
import com.coke.nestspace.assistant.LocalSpeechEngine
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Small one-shot voice dictation session for the assistant composer.
 *
 * Recognition runs fully on-device. [start] loads the cached Vosk model if
 * necessary, listens for one final sentence, and hands the text back so the
 * caller can put it into the normal text field.
 */
internal class VoiceDictation(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speechService: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var onFinal: ((String) -> Unit)? = null
    private var session = 0

    val preparing = mutableStateOf(false)
    val listening = mutableStateOf(false)
    val status = mutableStateOf(
        AppLanguage.tr("点按麦克风开始说话", "Tap the mic to speak")
    )
    val transcript = mutableStateOf("")

    fun start(onFinal: (String) -> Unit) {
        if (preparing.value || listening.value) return
        this.onFinal = onFinal
        transcript.value = ""
        status.value = AppLanguage.tr("正在准备本地语音模型…", "Preparing the local speech model…")
        val loaded = speechService
        if (loaded == null) {
            preparing.value = true
            val token = ++session
            scope.launch {
                try {
                    val language = FloatingDotSettings.load(context).speechLanguage
                    val model = LocalSpeechEngine.ensureLoaded(context, language)
                    val newRecognizer = withContext(Dispatchers.IO) {
                        Recognizer(model, SAMPLE_RATE)
                    }
                    recognizer = newRecognizer
                    speechService = SpeechService(newRecognizer, SAMPLE_RATE)
                    preparing.value = false
                    if (token != session) {
                        status.value = AppLanguage.tr("已停止语音输入", "Voice input stopped")
                        return@launch
                    }
                    beginListening()
                } catch (t: Throwable) {
                    preparing.value = false
                    listening.value = false
                    status.value = AppLanguage.tr(
                        "本地语音不可用：${t.message ?: t.javaClass.simpleName}",
                        "Voice is unavailable: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        } else {
            beginListening()
        }
    }

    fun stop() {
        session++
        onFinal = null
        listening.value = false
        runCatching { speechService?.cancel() }
        status.value = AppLanguage.tr("已停止语音输入", "Voice input stopped")
    }

    fun shutdown() {
        session++
        listening.value = false
        preparing.value = false
        onFinal = null
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { recognizer?.close() }
        recognizer = null
        scope.cancel()
    }

    private fun beginListening() {
        val service = speechService ?: return
        transcript.value = ""
        val started = runCatching { service.startListening(listener) }.getOrDefault(false)
        listening.value = started
        status.value = if (started) {
            AppLanguage.tr("正在聆听…", "Listening…")
        } else {
            AppLanguage.tr("无法启动麦克风", "Could not start the microphone")
        }
    }

    private fun finishSpeech(text: String) {
        if (text.isBlank()) {
            listening.value = false
            return
        }
        transcript.value = text
        listening.value = false
        status.value = AppLanguage.tr("识别完成", "Recognized")
        runCatching { speechService?.cancel() }
        val callback = onFinal
        onFinal = null
        if (callback != null) {
            scope.launch { callback(text) }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = hypothesisText(hypothesis, "partial")
            if (text.isNotBlank()) {
                transcript.value = text
                status.value = AppLanguage.tr("正在聆听…", "Listening…")
            }
        }

        override fun onResult(hypothesis: String?) {
            finishSpeech(hypothesisText(hypothesis, "text"))
        }

        override fun onFinalResult(hypothesis: String?) {
            if (listening.value) finishSpeech(hypothesisText(hypothesis, "text"))
        }

        override fun onError(exception: Exception?) {
            listening.value = false
            status.value = AppLanguage.tr(
                "语音识别失败：${exception?.message ?: "未知错误"}",
                "Speech recognition failed: ${exception?.message ?: "unknown error"}"
            )
        }

        override fun onTimeout() {
            listening.value = false
            status.value = AppLanguage.tr(
                "没有检测到语音，点按麦克风重试",
                "No speech detected; tap the mic to retry"
            )
        }
    }

    private fun hypothesisText(hypothesis: String?, field: String): String {
        if (hypothesis.isNullOrBlank()) return ""
        return runCatching {
            JSONObject(hypothesis).optString(field).trim()
        }.getOrElse {
            hypothesis.trim().takeIf { !it.startsWith("{") }.orEmpty()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000f
    }
}
