package com.coke.nestspace.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.coke.nestspace.plugin.PluginSpeechAudio
import com.coke.nestspace.plugin.PluginSpeechProvider
import com.coke.nestspace.plugin.PluginSpeechProviders
import com.coke.nestspace.plugin.PluginSpeechRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SpeechEnqueueResult(
    val requestId: String,
    val providerId: String,
    val providerName: String,
    val voiceId: String,
    val durationMs: Long
)

data class SpeechGatewayStatus(
    val activeRequestId: String?,
    val activeProviderId: String?,
    val queued: Int,
    val providers: List<PluginSpeechProvider>
)

class SpeechException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Host-owned speech synthesis and playback coordinator.
 *
 * Plugin providers only produce PCM audio. This gateway owns queueing, audio
 * focus, cancellation and speaker output so speech remains consistent when
 * plugins are disabled or replaced.
 */
object SpeechGateway {

    private const val TAG = "SpeechGateway"

    private data class PendingSpeech(
        val requestId: String,
        val providerId: String,
        val context: Context,
        val audio: PluginSpeechAudio,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val track: AtomicReference<AudioTrack?> = AtomicReference()
    )

    private data class InFlight(
        val providerId: String,
        val future: CompletableFuture<PluginSpeechAudio>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<PendingSpeech>(Channel.UNLIMITED)
    private val queued = AtomicInteger(0)
    private val active = AtomicReference<PendingSpeech?>()
    private val inFlight = ConcurrentHashMap<String, InFlight>()

    init {
        scope.launch {
            for (item in queue) {
                queued.decrementAndGet()
                if (item.cancelled.get()) continue
                play(item)
            }
        }
    }

    suspend fun speak(
        context: Context,
        text: String,
        providerId: String? = null,
        voiceId: String = "",
        locale: String = "",
        rate: Float = 1f,
        pitch: Float = 1f,
        interrupt: Boolean = false
    ): SpeechEnqueueResult {
        val provider = selectProvider(providerId)
        if (interrupt) stopAll()
        val requestId = UUID.randomUUID().toString()
        val request = PluginSpeechRequest(
            requestId,
            text,
            voiceId,
            locale,
            rate,
            pitch
        )
        val future = runCatching {
            provider.synthesizeAsync(request)
        }.getOrElse { error ->
            throw SpeechException(
                "SPEECH_PROVIDER_ERROR",
                error.message ?: "Speech provider failed to start",
                error
            )
        } ?: throw SpeechException(
            "SPEECH_PROVIDER_ERROR",
            "Speech provider returned no synthesis future"
        )
        inFlight[requestId] = InFlight(provider.providerId, future)
        val audio = try {
            future.awaitCancellable() ?: throw SpeechException(
                "SPEECH_PROVIDER_ERROR",
                "Speech provider returned no audio"
            )
        } catch (cancelled: CancellationException) {
            future.cancel(true)
            throw cancelled
        } catch (error: Throwable) {
            throw SpeechException(
                "SPEECH_SYNTHESIS_FAILED",
                error.message ?: "Speech synthesis failed",
                error
            )
        } finally {
            inFlight.remove(requestId)
        }
        if (provider.providerId != selectProviderOrNull(provider.providerId)?.providerId) {
            throw SpeechException(
                "SPEECH_PROVIDER_UNAVAILABLE",
                "Speech provider was disabled during synthesis"
            )
        }
        queued.incrementAndGet()
        try {
            queue.send(
                PendingSpeech(
                    requestId = requestId,
                    providerId = provider.providerId,
                    context = context.applicationContext,
                    audio = audio
                )
            )
        } catch (error: Throwable) {
            queued.decrementAndGet()
            throw error
        }
        return SpeechEnqueueResult(
            requestId = requestId,
            providerId = provider.providerId,
            providerName = provider.displayName,
            voiceId = request.voiceId,
            durationMs = audio.durationMs
        )
    }

    fun stopAll(): Int {
        inFlight.values.forEach { it.future.cancel(true) }
        inFlight.clear()
        var stopped = 0
        active.getAndSet(null)?.let {
            it.cancelled.set(true)
            it.track.get()?.let { track -> runCatching { track.pause() } }
            stopped++
        }
        while (true) {
            val item = queue.tryReceive().getOrNull() ?: break
            item.cancelled.set(true)
            queued.decrementAndGet()
            stopped++
        }
        return stopped
    }

    fun stopProvider(providerId: String): Int {
        inFlight.entries
            .filter { it.value.providerId == providerId }
            .forEach {
                it.value.future.cancel(true)
                inFlight.remove(it.key)
            }
        var stopped = 0
        active.get()?.takeIf { it.providerId == providerId }?.let {
            it.cancelled.set(true)
            it.track.get()?.let { track -> runCatching { track.pause() } }
            stopped++
        }
        val retained = mutableListOf<PendingSpeech>()
        while (true) {
            val item = queue.tryReceive().getOrNull() ?: break
            queued.decrementAndGet()
            if (item.providerId == providerId) {
                item.cancelled.set(true)
                stopped++
            } else {
                retained += item
            }
        }
        retained.forEach {
            queue.trySend(it)
            queued.incrementAndGet()
        }
        return stopped
    }

    fun status(): SpeechGatewayStatus {
        val current = active.get()
        return SpeechGatewayStatus(
            activeRequestId = current?.requestId,
            activeProviderId = current?.providerId,
            queued = queued.get().coerceAtLeast(0),
            providers = PluginSpeechProviders.snapshot()
        )
    }

    private fun selectProvider(providerId: String?): PluginSpeechProvider {
        val id = providerId.orEmpty().trim()
        val provider = if (id.isEmpty()) {
            PluginSpeechProviders.snapshot().firstOrNull()
        } else {
            PluginSpeechProviders.find(id)
        }
        return provider ?: throw SpeechException(
            if (id.isEmpty()) {
                "SPEECH_PROVIDER_UNAVAILABLE"
            } else {
                "SPEECH_PROVIDER_NOT_FOUND"
            },
            if (id.isEmpty()) {
                "No speech provider plugin is enabled"
            } else {
                "Speech provider is not available: $id"
            }
        )
    }

    private fun selectProviderOrNull(providerId: String): PluginSpeechProvider? =
        PluginSpeechProviders.find(providerId)

    private suspend fun play(item: PendingSpeech) {
        if (item.cancelled.get()) return
        active.set(item)
        val audioManager =
            item.context.getSystemService(AudioManager::class.java)
        val focus = audioManager?.let { requestAudioFocus(it) }
        val minBuffer = AudioTrack.getMinBufferSize(
            item.audio.sampleRateHz,
            channelMask(item.audio.channels),
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            active.compareAndSet(item, null)
            abandonAudioFocus(audioManager, focus)
            Log.w(TAG, "invalid AudioTrack buffer size: $minBuffer")
            return
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(item.audio.sampleRateHz)
                        .setChannelMask(channelMask(item.audio.channels))
                        .build()
                )
                .setBufferSizeInBytes(
                    maxOf(minBuffer, item.audio.pcm16Le.size.coerceAtMost(64 * 1024))
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse {
            active.compareAndSet(item, null)
            abandonAudioFocus(audioManager, focus)
            Log.w(TAG, "cannot create speech AudioTrack", it)
            return
        }
        item.track.set(track)
        try {
            val bytes = item.audio.pcm16Le
            val frameBytes = item.audio.channels * 2
            var offset = 0
            track.play()
            while (offset < bytes.size && !item.cancelled.get()) {
                val remaining = bytes.size - offset
                val chunk = minOf(32 * 1024, remaining).let {
                    it - (it % frameBytes)
                }.coerceAtLeast(frameBytes)
                val written = track.write(
                    bytes,
                    offset,
                    chunk,
                    AudioTrack.WRITE_BLOCKING
                )
                if (written < 0) {
                    Log.w(TAG, "AudioTrack write failed: $written")
                    break
                }
                offset += written
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            item.track.set(null)
            active.compareAndSet(item, null)
            abandonAudioFocus(audioManager, focus)
        }
    }

    private fun channelMask(channels: Int): Int =
        if (channels == 1) AudioFormat.CHANNEL_OUT_MONO
        else AudioFormat.CHANNEL_OUT_STEREO

    private class FocusHolder(
        val request: AudioFocusRequest?,
        val listener: AudioManager.OnAudioFocusChangeListener?
    )

    private fun requestAudioFocus(audioManager: AudioManager): FocusHolder {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                active.get()?.let { item ->
                    item.cancelled.set(true)
                    runCatching { item.track.get()?.pause() }
                }
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            audioManager.requestAudioFocus(request)
            FocusHolder(request, listener)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            FocusHolder(null, listener)
        }
    }

    private fun abandonAudioFocus(
        audioManager: AudioManager?,
        holder: FocusHolder?
    ) {
        if (audioManager == null || holder == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            holder.request?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            holder.listener?.let(audioManager::abandonAudioFocus)
        }
    }
}

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (!continuation.isActive) return@whenComplete
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
