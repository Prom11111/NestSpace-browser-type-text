package com.coke.nestspace.assistant

import com.coke.nestspace.i18n.AppLanguage
import android.Manifest
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.coke.nestspace.ui.FrameSpinner
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.ui.agent.UserGateHost
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
    private var resumeAfterReply = true
    private var userEdited = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onInputChange = {
                        userEdited = true
                        input = it
                    },
                    onSend = { submitInput() },
                    onStop = { AssistantConversation.cancel() },
                    onToggleListening = { toggleListening() },
                    onToggleInput = { toggleInput() },
                    onNewConversation = { newConversation() },
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

        if (hasAudioPermission()) {
            prepareLocalSpeech()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
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
        FloatingDotOverlay.setSuppressed(this, true)
        FloatingDotOverlay.onHostActivityResumed(this)
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

    private fun newConversation() {
        if (busy) {
            Toast.makeText(
                this,
                AppLanguage.tr("正在处理，请稍候", "Still working, please wait"),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        stopListening(cancel = true)
        AssistantConversation.resetToWelcome()
        transcript = ""
        input = ""
        userEdited = false
        status = when {
            preparing -> AppLanguage.tr(
                            "正在准备本地语音模型…",
                            "Preparing the local speech model…"
                        )
            available -> AppLanguage.tr("正在本地聆听…", "Listening on device…")
            else -> AppLanguage.tr("输入你的问题", "Type your question")
        }
        if (available && !busy) startListening()
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
        return runCatching {
            JSONObject(hypothesis).optString(field).trim()
        }.getOrElse {
            hypothesis.trim().takeIf { !it.startsWith("{") }.orEmpty()
        }
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
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onToggleListening: () -> Unit,
    onToggleInput: () -> Unit,
    onNewConversation: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val accent = remember { Color(FloatingDotSettings.load(context).hostColor) }
    val visible = remember(messages) {
        messages.filter {
            it.role == AgentMessage.Role.USER ||
                it.role == AgentMessage.Role.AGENT ||
                it.role == AgentMessage.Role.ERROR
        }
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(visible.size, busy, showInput) {
        val footerIndex = visible.size + if (busy) 1 else 0
        listState.animateScrollToItem(footerIndex)
    }
    LaunchedEffect(showInput) {
        if (showInput) runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xCC090A11),
                        Color(0xD9141522),
                        Color(0xCC090A11)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    AppLanguage.tr("AI 助手", "AI assistant"),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = AppLanguage.tr("关闭", "Close"), tint = Color.White)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(visible) { _, message ->
                    ChatBubble(message = message, accent = accent)
                }
                if (busy) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FrameSpinner(
                                        modifier = Modifier.size(15.dp),
                                        color = accent,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        AppLanguage.tr("正在思考…", "Thinking…"),
                                        color = Color.White.copy(alpha = 0.78f),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(1.dp))
                }
            }

            if (!showInput) {
                if (preparing) {
                    ModelPreparingCard(
                        percent = downloadPercent,
                        status = status,
                        accent = accent
                    )
                } else {
                    Text(
                        text = transcript.ifBlank { status },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(
                            alpha = if (transcript.isBlank()) 0.66f else 0.92f
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (busy) {
                            Box(
                                modifier = Modifier
                                    .size(124.dp)
                                    .background(accent.copy(alpha = 0.10f), CircleShape)
                                    .clickable(onClick = onStop),
                                contentAlignment = Alignment.Center
                            ) {
                                FrameSpinner(
                                    modifier = Modifier.size(118.dp),
                                    color = accent,
                                    strokeWidth = 3.dp
                                )
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(36.dp)
                                )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleInput) {
                        Icon(
                            Icons.Default.Keyboard,
                            contentDescription = AppLanguage.tr("输入文字", "Type text"),
                            tint = Color.White.copy(alpha = 0.72f)
                        )
                    }
                    Text(
                        when {
                            busy -> AppLanguage.tr("正在处理，点按圆点停止", "Still working; tap the dot to stop")
                            preparing -> AppLanguage.tr(
                                "模型准备中，可先用键盘输入",
                                "Preparing the model; you can type meanwhile"
                            )
                            listening -> AppLanguage.tr("点按圆点停止", "Tap the dot to stop")
                            available -> AppLanguage.tr("点按圆点开始说话", "Tap the dot to start speaking")
                            else -> AppLanguage.tr("语音不可用，可点键盘输入", "Voice is unavailable; use the keyboard")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    IconButton(onClick = onNewConversation) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = AppLanguage.tr("新对话", "New conversation"),
                            tint = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            if (busy) {
                                AppLanguage.tr(
                                    "正在处理，点右侧停止…",
                                    "Working… tap stop on the right"
                                )
                            } else {
                                AppLanguage.tr("输入你的问题…", "Type your question…")
                            }
                        )
                    },
                    minLines = 1,
                    maxLines = 4,
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
                        unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
                        cursorColor = accent,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                        focusedPlaceholderColor = Color.White.copy(alpha = 0.45f),
                        unfocusedPlaceholderColor = Color.White.copy(alpha = 0.45f)
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        status,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onToggleInput) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = AppLanguage.tr("语音输入", "Voice input"),
                            tint = accent
                        )
                    }
                }
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
            .size(148.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .scale(pulse)
                .background(accent.copy(alpha = 0.12f), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(112.dp)
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
                .size(100.dp)
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
            modifier = Modifier.size(36.dp)
        )
    }
}
