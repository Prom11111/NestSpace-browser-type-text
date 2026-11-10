package com.coke.nestspace.assistant

import com.coke.nestspace.i18n.AppLanguage
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.browser.BrowserSession
import com.coke.nestspace.ui.FrameSpinner
import com.coke.nestspace.MainActivity
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.data.LlmConfigStore
import com.coke.nestspace.ui.ModelSettingsNavigation
import com.coke.nestspace.ui.agent.UserGateHost
import com.coke.nestspace.ui.agent.DownloadProgressCard
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.ui.markdown.MarkdownBlock
import com.coke.nestspace.ui.markdown.markdownAnnotatedString
import com.coke.nestspace.ui.markdown.parseMarkdown
import com.coke.nestspace.ui.theme.VirtualTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService

/**
 * Siri/ChatGPT-style voice conversation sheet opened from the floating dot.
 *
 * The text field is hidden until the user taps the keyboard control. Voice
 * recognition starts automatically, final speech results are submitted
 * without an extra tap, and the sheet stays open for follow-up turns.
 * Messages and LLM history live in [AssistantConversation], shared with the
 * assistant tab.
 */
class AssistantVoiceActivity : ComponentActivity() {

    private var voskRecognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var speechLanguage = FloatingDotSettings.SPEECH_LANG_ZH
    private var isListening by mutableStateOf(false)
    private var available by mutableStateOf(false)
    private var preparing by mutableStateOf(false)
    private var downloadPercent by mutableStateOf<Int?>(null)
    private var status by mutableStateOf(
        AppLanguage.tr("正在准备本地语音模型…", "Preparing the local speech model…")
    )
    private var transcript by mutableStateOf("")
    private var input by mutableStateOf("")
    private var level by mutableFloatStateOf(0f)
    private var showInput by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var modelConfigured by mutableStateOf(false)
    private var speechBootstrapped = false
    private var resumeAfterReply = true
    private var userEdited = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelConfigured = hasConfiguredModel()
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            VirtualTheme(darkTheme = true, dynamicColor = false) {
                val messages by AssistantConversation.messages.collectAsState()
                AssistantVoiceScreen(
                    messages = messages,
                    busy = busy,
                    preparing = preparing,
                    downloadPercent = downloadPercent,
                    listening = isListening,
                    available = available,
                    status = status,
                    transcript = transcript,
                    input = input,
                    level = level,
                    showInput = showInput,
                    modelConfigured = modelConfigured,
                    onInputChange = {
                        userEdited = true
                        input = it
                    },
                    onSend = { submitInput() },
                    onStop = { AssistantConversation.cancel() },
                    onToggleListening = { toggleListening() },
                    onToggleInput = { toggleInput() },
                    onConfigureModel = { openModelSettings() },
                    onBack = { backOrClose() },
                    onClose = { finish() }
                )
                UserGateHost()
            }
        }

        lifecycleScope.launch {
            AssistantConversation.busy.collect { value ->
                busy = value
                if (value) {
                    status = AppLanguage.tr("正在思考…", "Thinking…")
                } else if (resumeAfterReply) {
                    // Never open the microphone while this Activity is not
                    // resumed. Keep resumeAfterReply set so onResume can
                    // listen when the user comes back.
                    if (!resumed) return@collect
                    resumeAfterReply = false
                    when {
                        showInput -> status = AppLanguage.tr("输入你的问题", "Type your question")
                        preparing -> status = AppLanguage.tr(
                            "正在准备本地语音模型…",
                            "Preparing the local speech model…"
                        )
                        available && hasAudioPermission() -> startListening()
                        else -> status = if (available) {
                            AppLanguage.tr("点按圆点开始说话", "Tap the dot to start speaking")
                        } else {
                            AppLanguage.tr("语音不可用，可点键盘输入", "Voice is unavailable; use the keyboard")
                        }
                    }
                } else if (status == AppLanguage.tr("正在思考…", "Thinking…") ||
                    status == AppLanguage.tr(
                        "已加入当前任务…",
                        "Added to the current task…"
                    )
                ) {
                    status = when {
                        showInput -> AppLanguage.tr("输入你的问题", "Type your question")
                        preparing -> AppLanguage.tr(
                            "正在准备本地语音模型…",
                            "Preparing the local speech model…"
                        )
                        available -> AppLanguage.tr("点按圆点开始说话", "Tap the dot to start speaking")
                        else -> AppLanguage.tr("语音不可用，可点键盘输入", "Voice is unavailable; use the keyboard")
                    }
                }
            }
        }

        lifecycleScope.launch {
            LocalSpeechModelStore.progress.collect { progress ->
                if (!preparing || showInput) return@collect
                val percent = progress[speechLanguage] ?: return@collect
                downloadPercent = percent
                val spec = LocalSpeechModelStore.spec(speechLanguage)
                val mirror = LocalSpeechModelStore.source.value[speechLanguage]
                status = when {
                    percent < 0 -> AppLanguage.tr(
                        "本地语音模型下载失败，点击键盘输入文字",
                        "Speech model download failed; tap the keyboard to type"
                    )
                    percent >= 100 -> AppLanguage.tr(
                        AppLanguage.tr("正在加载本地语音模型…", "Loading the local speech model…"),
                        "Loading the local speech model…"
                    )
                    percent == 0 -> AppLanguage.tr(
                        "正在选择最快的模型服务器…",
                        "Choosing the fastest model server…"
                    )
                    else -> AppLanguage.tr(
                        "从${mirror ?: "模型服务器"}下载" +
                            "${spec.displayName}模型 $percent%" +
                            "（约 ${spec.approximateMb}MB，仅首次）",
                        "Downloading the ${spec.displayName} model from " +
                            "${mirror ?: "the model server"} $percent% " +
                            "(about ${spec.approximateMb} MB, first time only)"
                    )
                }
            }
        }

        bootstrapSpeechIfConfigured()
    }

    /**
     * Back belongs to the mirrored app while its small window is visible;
     * the assistant sheet is closed with the X instead. Without this, the
     * host target sits behind this Activity and never sees the back key.
     */
    private fun backOrClose() {
        val slot = HeadlessMirrorWindow.activeSlot()
        if (slot < 0) {
            finish()
            return
        }
        lifecycleScope.launch {
            if (ContainerUiClient.pressBack(this@AssistantVoiceActivity, slot) != true) {
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        modelConfigured = hasConfiguredModel()
        FloatingDotOverlay.setSuppressed(this, true)
        FloatingDotOverlay.onHostActivityResumed(this)
        bootstrapSpeechIfConfigured()
        // If the reply finished while the sheet was in the background, start
        // listening only once the Activity is visible again.
        if (resumeAfterReply && !busy && !preparing && !showInput &&
            available && hasAudioPermission()
        ) {
            resumeAfterReply = false
            startListening()
        }
    }

    override fun onPause() {
        resumed = false
        stopListening(cancel = true)
        // The sheet may be paused by another app/container rather than
        // destroyed; don't keep the dot hidden behind someone else's UI.
        FloatingDotOverlay.setSuppressed(this, false)
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            prepareLocalSpeech()
        } else {
            available = false
            status = AppLanguage.tr(
                "需要麦克风权限，点击键盘输入文字",
                "Microphone permission needed; tap the keyboard to type"
            )
        }
    }

    override fun onDestroy() {
        resumeAfterReply = false
        resumed = false
        stopListening(cancel = true)
        speechService?.shutdown()
        speechService = null
        voskRecognizer?.close()
        voskRecognizer = null
        FloatingDotOverlay.setSuppressed(this, false)
        super.onDestroy()
    }

    private fun hasAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasConfiguredModel(): Boolean {
        val state = LlmConfigStore(this).load()
        val active = state.configs.firstOrNull { it.id == state.activeId }
            ?: state.configs.firstOrNull()
        return active?.isConfigured == true
    }

    private fun bootstrapSpeechIfConfigured() {
        if (!modelConfigured || speechBootstrapped) return
        speechBootstrapped = true
        if (hasAudioPermission()) {
            prepareLocalSpeech()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    private fun openModelSettings() {
        stopListening(cancel = true)
        ModelSettingsNavigation.requestModelSettings()
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        finish()
    }

    private fun prepareLocalSpeech() {
        if (speechService != null) {
            available = true
            if (!busy && !showInput) startListening()
            return
        }
        if (preparing) return
        speechLanguage = FloatingDotSettings.load(this).speechLanguage
        val spec = LocalSpeechModelStore.spec(speechLanguage)
        preparing = true
        available = false
        downloadPercent = if (LocalSpeechModelStore.isReady(this, speechLanguage)) {
            null
        } else {
            0
        }
        status = if (LocalSpeechModelStore.isReady(this, speechLanguage)) {
            AppLanguage.tr("正在加载本地语音模型…", "Loading the local speech model…")
        } else {
            AppLanguage.tr(
                "正在选择最快的模型服务器…（${spec.displayName}，约 ${spec.approximateMb}MB）",
                "Choosing the fastest model server… (${spec.displayName}, " +
                    "about ${spec.approximateMb} MB)"
            )
        }
        lifecycleScope.launch {
            try {
                LocalSpeechModelStore.ensure(
                    this@AssistantVoiceActivity,
                    speechLanguage
                )
                status = AppLanguage.tr("正在加载本地语音模型…", "Loading the local speech model…")
                val model = LocalSpeechEngine.ensureLoaded(
                    this@AssistantVoiceActivity,
                    speechLanguage
                )
                val recognizer = withContext(Dispatchers.IO) {
                    Recognizer(model, SAMPLE_RATE)
                }
                voskRecognizer = recognizer
                speechService = SpeechService(recognizer, SAMPLE_RATE)
                preparing = false
                downloadPercent = null
                available = true
                if (resumed && !busy && !showInput) {
                    startListening()
                } else {
                    status = if (showInput) AppLanguage.tr("输入你的问题", "Type your question")
                    else AppLanguage.tr("点按圆点开始说话", "Tap the dot to start speaking")
                }
            } catch (cancelled: CancellationException) {
                preparing = false
                downloadPercent = null
                throw cancelled
            } catch (t: Throwable) {
                preparing = false
                downloadPercent = null
                available = false
                status = AppLanguage.tr(
                    "本地语音初始化失败：${t.message ?: t.javaClass.simpleName}",
                    "Local speech init failed: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    private fun toggleInput() {
        showInput = !showInput
        if (showInput) {
            stopListening(cancel = true)
            resumeAfterReply = false
            status = AppLanguage.tr("输入你的问题", "Type your question")
        } else {
            resumeAfterReply = false
            status = when {
                preparing -> AppLanguage.tr(
                            "正在准备本地语音模型…",
                            "Preparing the local speech model…"
                        )
                available -> AppLanguage.tr("正在本地聆听…", "Listening on device…")
                else -> AppLanguage.tr("本地语音不可用", "Local speech unavailable")
            }
            if (available && !busy) startListening()
        }
    }

    private fun toggleListening() {
        if (preparing) {
            Toast.makeText(
                this,
                AppLanguage.tr("本地语音模型准备中，请稍候", "Preparing the speech model, please wait"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!available) {
            showInput = true
            resumeAfterReply = false
            status = AppLanguage.tr(
                "本地语音不可用，请输入文字",
                "Local speech unavailable; please type"
            )
            return
        }
        if (busy) {
            Toast.makeText(
                this,
                AppLanguage.tr("正在处理，请稍候", "Still working, please wait"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (isListening) {
            stopListening(cancel = true)
            resumeAfterReply = false
            status = AppLanguage.tr(
                "已停止，点按圆点继续或使用键盘输入",
                "Stopped. Tap the dot to continue or use the keyboard"
            )
        } else {
            transcript = ""
            startListening()
        }
    }

    private fun startListening() {
        val service = speechService ?: return
        if (isListening) return
        val started = runCatching {
            service.startListening(VoskListener())
        }.getOrDefault(false)
        if (!started) {
            isListening = false
            status = AppLanguage.tr(
                "无法启动本地麦克风，请用键盘输入",
                "Could not start the microphone; please use the keyboard"
            )
            return
        }
        isListening = true
        level = 0f
        transcript = ""
        userEdited = false
        status = AppLanguage.tr("正在本地聆听…", "Listening on device…")
    }

    private fun stopListening(cancel: Boolean) {
        if (!isListening) return
        isListening = false
        level = 0f
        runCatching {
            if (cancel) speechService?.cancel() else speechService?.stop()
        }
    }

    private fun applySpeech(text: String, final: Boolean) {
        if (text.isBlank()) return
        transcript = text
        if (!userEdited || final) input = text
        if (final) {
            isListening = false
            level = 0f
            status = AppLanguage.tr("识别完成", "Recognized")
        }
    }

    private fun submitInput() {
        val text = input.trim().ifBlank { transcript.trim() }
        submit(text)
    }

    private fun submit(text: String) {
        if (text.isBlank()) return
        if (!modelConfigured) {
            openModelSettings()
            return
        }
        stopListening(cancel = true)
        transcript = ""
        userEdited = false
        // submit() flips busy synchronously, so capture the prior state: a
        // failed/instant run must not leave the "queued" label behind.
        val wasBusy = AssistantConversation.busy.value
        if (!AssistantConversation.submit(this, text)) {
            status = AppLanguage.tr(
                "发送失败，请重试",
                "Could not send; try again"
            )
            return
        }
        input = ""
        // Release the IME after sending. Otherwise the keyboard (or Gboard's
        // floating toolbar) consumes the first back press before the mirror
        // can receive it.
        currentFocus?.let { view ->
            getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        status = if (wasBusy) {
            AppLanguage.tr("已加入当前任务…", "Added to the current task…")
        } else {
            AppLanguage.tr("正在思考…", "Thinking…")
        }
        resumeAfterReply = true
    }

    private inner class VoskListener : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (!isListening) return
            val text = hypothesisText(hypothesis, "partial")
            if (text.isNotBlank()) {
                applySpeech(text, final = false)
                status = AppLanguage.tr("正在本地聆听…", "Listening on device…")
            }
        }

        override fun onResult(hypothesis: String?) {
            val text = hypothesisText(hypothesis, "text")
            if (text.isBlank()) return
            applySpeech(text, final = true)
            speechService?.cancel()
            if (!showInput) submit(text)
        }

        override fun onFinalResult(hypothesis: String?) {
            if (!isListening) return
            val text = hypothesisText(hypothesis, "text")
            if (text.isBlank()) return
            applySpeech(text, final = true)
            speechService?.cancel()
            if (!showInput) submit(text)
        }

        override fun onError(exception: Exception?) {
            isListening = false
            level = 0f
            status = AppLanguage.tr(
                "本地语音识别失败：${exception?.message ?: "未知错误"}，请用键盘输入",
                "Speech recognition failed: ${exception?.message ?: "unknown error"}; " +
                    "please use the keyboard"
            )
        }

        override fun onTimeout() {
            isListening = false
            level = 0f
            status = AppLanguage.tr(
                "没有检测到语音，点按圆点重试或用键盘输入",
                "No speech detected. Tap the dot to retry or use the keyboard"
            )
        }
    }

    private fun hypothesisText(hypothesis: String?, field: String): String {
        if (hypothesis.isNullOrBlank()) return ""
        val raw = runCatching {
            JSONObject(hypothesis).optString(field).trim()
        }.getOrElse {
            hypothesis.trim().takeIf { !it.startsWith("{") }.orEmpty()
        }
        return normalizeSpeechText(raw)
    }

    private companion object {
        const val REQUEST_AUDIO = 0xA51
        const val SAMPLE_RATE = 16_000f
    }
}

@Composable
private fun AssistantVoiceScreen(
    messages: List<AgentMessage>,
    busy: Boolean,
    preparing: Boolean,
    downloadPercent: Int?,
    listening: Boolean,
    available: Boolean,
    status: String,
    transcript: String,
    input: String,
    level: Float,
    showInput: Boolean,
    modelConfigured: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleListening: () -> Unit,
    onToggleInput: () -> Unit,
    onConfigureModel: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val accent = remember { Color(FloatingDotSettings.load(context).hostColor) }
    val focusRequester = remember { FocusRequester() }
    val downloads by BrowserSession.downloadsFlow.collectAsState()
    val activeDownload = downloads.firstOrNull { it.state == "downloading" }
    val latestReply = remember(messages) {
        messages.lastOrNull {
            it.role == AgentMessage.Role.AGENT && !it.welcome
        }?.text?.trim().orEmpty()
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(showInput) {
        if (showInput) runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(Color.Black.copy(alpha = 0.24f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(30.dp),
            color = Color(0xF21A1B22),
            shadowElevation = 18.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        AppLanguage.tr("NestSpace 助手", "NestSpace Assistant"),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = AppLanguage.tr("关闭", "Close"),
                            tint = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }

                if (!modelConfigured) {
                    ModelSetupPrompt(
                        accent = accent,
                        onConfigure = onConfigureModel
                    )
                } else if (preparing) {
                    Text(
                        status,
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        progress = {
                            downloadPercent?.takeIf { it in 0..100 }?.div(100f) ?: 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = accent
                    )
                } else {
                    Text(
                        transcript.ifBlank { status },
                        color = Color.White.copy(alpha = if (transcript.isBlank()) 0.62f else 0.90f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!busy && latestReply.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = Color.White.copy(alpha = 0.08f)
                        ) {
                            Text(
                                latestReply,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                color = Color.White.copy(alpha = 0.92f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!showInput) {
                        if (busy) {
                            Surface(
                                modifier = Modifier.clickable(onClick = onStop),
                                shape = RoundedCornerShape(50),
                                color = accent.copy(alpha = 0.16f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FrameSpinner(
                                        modifier = Modifier.size(18.dp),
                                        color = accent,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(9.dp))
                                    Text(
                                        AppLanguage.tr("正在处理 · 点击停止", "Working · tap to stop"),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        } else {
                            VoiceOrb(
                                listening = listening,
                                level = level,
                                accent = accent,
                                onClick = onToggleListening
                            )
                        }
                    }
                }

                activeDownload?.let { download ->
                    DownloadProgressCard(
                        download = download,
                        dark = true
                    )
                }

                if (modelConfigured && showInput) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(AppLanguage.tr("说点什么…", "Ask me anything…"))
                        },
                        minLines = 1,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { if (busy) onStop() else onSend() }
                        ),
                        trailingIcon = {
                            IconButton(onClick = { if (busy) onStop() else onSend() }) {
                                Icon(
                                    if (busy) Icons.Default.Stop else Icons.Default.Send,
                                    contentDescription = if (busy) {
                                        AppLanguage.tr("停止", "Stop")
                                    } else {
                                        AppLanguage.tr("发送", "Send")
                                    },
                                    tint = accent
                                )
                            }
                        },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.24f),
                            cursorColor = accent,
                            focusedContainerColor = Color.White.copy(alpha = 0.06f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                            focusedPlaceholderColor = Color.White.copy(alpha = 0.42f),
                            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.42f)
                        )
                    )
                }

                if (modelConfigured) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onToggleInput) {
                            Icon(
                                if (showInput) Icons.Default.Mic else Icons.Default.Keyboard,
                                contentDescription = if (showInput) {
                                    AppLanguage.tr("切换到语音", "Switch to voice")
                                } else {
                                    AppLanguage.tr("切换到键盘", "Switch to keyboard")
                                },
                                tint = Color.White.copy(alpha = 0.72f)
                            )
                        }
                        Text(
                            when {
                                busy -> AppLanguage.tr("正在处理", "Working")
                                preparing -> AppLanguage.tr("正在准备语音", "Preparing voice")
                                listening -> AppLanguage.tr("正在聆听", "Listening")
                                available -> AppLanguage.tr("点按圆点开始", "Tap the dot to start")
                                else -> AppLanguage.tr("语音不可用", "Voice unavailable")
                            },
                            modifier = Modifier.weight(1f),
                            color = Color.White.copy(alpha = 0.48f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSetupPrompt(
    accent: Color,
    onConfigure: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = accent.copy(alpha = 0.13f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Text(
                AppLanguage.tr("请先配置模型", "Configure a model first"),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                AppLanguage.tr(
                    "配置后即可使用语音和文字对话",
                    "Configure one to use voice and text chat"
                ),
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onConfigure),
                shape = RoundedCornerShape(50),
                color = accent
            ) {
                Text(
                    AppLanguage.tr("去配置", "Configure"),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: AgentMessage, accent: Color) {
    val isUser = message.role == AgentMessage.Role.USER
    val isError = message.role == AgentMessage.Role.ERROR
    val bubbleColor = when {
        isUser -> accent.copy(alpha = 0.82f)
        isError -> Color(0xFF8C1D18).copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 5.dp,
                bottomEnd = if (isUser) 5.dp else 18.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            MessageText(
                text = message.text,
                accent = accent,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun MessageText(text: String, accent: Color, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            if (block is MarkdownBlock.Code) {
                CodeBlock(block)
            } else {
                Text(
                    text = markdownAnnotatedString(
                        text = block.plainText(),
                        inlineCodeColor = accent,
                        linkColor = accent
                    ),
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.32f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            if (block.language.isNotBlank()) {
                Text(
                    block.language,
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Text(
                text = block.code,
                color = Color.White.copy(alpha = 0.92f),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

private fun MarkdownBlock.plainText(): String = when (this) {
    is MarkdownBlock.Prose -> text
    is MarkdownBlock.Heading -> text
    is MarkdownBlock.Bullet -> "• $text"
    is MarkdownBlock.Numbered -> "$number. $text"
    is MarkdownBlock.Quote -> text
    is MarkdownBlock.Table ->
        (listOf(headers) + rows).joinToString("\n") { it.joinToString(" | ") }
    is MarkdownBlock.Code -> code
    MarkdownBlock.Divider -> "———"
}

@Composable
private fun ModelPreparingCard(
    percent: Int?,
    status: String,
    accent: Color
) {
    val downloading = percent != null && percent in 1..99
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        color = Color.White.copy(alpha = 0.07f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    AppLanguage.tr(
                        "正在准备本地语音模型",
                        "Preparing the local speech model"
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                status,
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (downloading) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
                Text(
                    "${percent}%",
                    color = accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = accent
                )
            }
        }
    }
}

@Composable
private fun VoiceOrb(
    listening: Boolean,
    level: Float,
    accent: Color,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "voice-orb")
    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = if (listening) 1.06f + level * 0.05f else 1.01f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice-pulse"
    )
    Box(
        modifier = Modifier
            .size(112.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(106.dp)
                .scale(pulse)
                .background(accent.copy(alpha = 0.12f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(86.dp)
                .scale(pulse)
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.98f),
                            accent.copy(alpha = 0.72f)
                        )
                    ),
                    CircleShape
                )
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = if (listening) 0.60f else 0.28f),
                    shape = CircleShape
                )
        )
        Icon(
            Icons.Default.Mic,
            contentDescription = AppLanguage.tr("语音输入", "Voice input"),
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}
