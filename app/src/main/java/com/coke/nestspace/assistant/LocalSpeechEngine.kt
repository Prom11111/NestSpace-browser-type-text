package com.coke.nestspace.assistant

import com.coke.nestspace.i18n.AppLanguage
import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model

/**
 * Process-wide Vosk model cache.
 *
 * Loading the acoustic model takes several seconds, so once a model is
 * downloaded it is loaded in the background and reused by the voice sheet.
 * One model per language stays loaded for the life of the process, so
 * switching back and forth does not reload or leak copies.
 */
internal object LocalSpeechEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var model: Model? = null
    private var language: String? = null
    // A paused voice Activity may still hold a Recognizer for the previous
    // language. Keep at most one loaded model per language (zh/en), instead
    // of retaining a new copy on every language switch.
    private val loadedModels = mutableMapOf<String, Model>()
    private val _loadedLanguage = MutableStateFlow<String?>(null)
    private val _loadingLanguage = MutableStateFlow<String?>(null)

    /** Language whose model is ready for use, if any. */
    val loadedLanguage: StateFlow<String?> = _loadedLanguage
    val loadingLanguage: StateFlow<String?> = _loadingLanguage

    fun preload(context: Context, language: String) {
        if (!LocalSpeechModelStore.isReady(context, language)) return
        scope.launch {
            runCatching { ensureLoaded(context, language) }
        }
    }

    suspend fun ensureLoaded(context: Context, language: String): Model = mutex.withLock {
        val current = model
        if (current != null && this.language == language) {
            _loadedLanguage.value = language
            return@withLock current
        }
        loadedModels[language]?.let { cached ->
            model = cached
            this.language = language
            _loadedLanguage.value = language
            return@withLock cached
        }
        if (!LocalSpeechModelStore.isReady(context, language)) {
            throw IOException(AppLanguage.tr("本地语音模型尚未下载", "The local speech model is not downloaded yet"))
        }
        _loadingLanguage.value = language
        try {
            val loaded = withContext(Dispatchers.IO) {
                Model(LocalSpeechModelStore.modelDir(context, language).absolutePath)
            }
            loadedModels[language] = loaded
            model = loaded
            this.language = language
            _loadedLanguage.value = language
            loaded
        } finally {
            if (_loadingLanguage.value == language) {
                _loadingLanguage.value = null
            }
        }
    }
}
