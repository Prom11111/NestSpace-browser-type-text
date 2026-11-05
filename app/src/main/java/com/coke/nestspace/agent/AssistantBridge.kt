package com.coke.nestspace.agent

import com.coke.nestspace.data.AgentMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process bridge between the MCP server and the assistant UI.
 *
 * MCP tools can type into the assistant input field, submit the message, and
 * inspect the resulting conversation without using accessibility key events.
 */
object AssistantBridge {

    data class Snapshot(
        val busy: Boolean = false,
        val messages: List<AgentMessage> = emptyList()
    )

    private val _pendingText = MutableStateFlow<String?>(null)
    val pendingText: StateFlow<String?> = _pendingText

    private val _currentInput = MutableStateFlow("")
    val currentInput: StateFlow<String> = _currentInput

    private val _sendRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sendRequests: SharedFlow<String> = _sendRequests

    private val _clearInput = MutableStateFlow(0)
    val clearInput: StateFlow<Int> = _clearInput

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    /**
     * Prompt submitted from outside the assistant screen (floating dot voice
     * sheet). The UI collects it, switches to the assistant tab and sends it.
     */
    private val _externalRequest = MutableStateFlow<String?>(null)
    val externalRequest: StateFlow<String?> = _externalRequest

    fun request(text: String) {
        if (text.isNotBlank()) _externalRequest.value = text
    }

    fun consumeExternalRequest() {
        _externalRequest.value = null
    }

    @Volatile
    private var screenAttached = false

    fun setScreenAttached(attached: Boolean) {
        screenAttached = attached
    }

    fun isScreenAttached(): Boolean = screenAttached

    fun type(text: String): Boolean {
        if (_snapshot.value.busy || text.isBlank()) return false
        _currentInput.value = text
        _pendingText.value = text
        return true
    }

    fun updateInput(text: String) {
        _currentInput.value = text
    }

    fun consumePending(): String? {
        val value = _pendingText.value ?: return null
        _pendingText.value = null
        return value
    }

    fun send(text: String? = null): Boolean {
        // A message sent while busy is steering: AssistantConversation.submit
        // injects it into the running task instead of rejecting it.
        val payload = text?.takeIf { it.isNotBlank() }
            ?: _currentInput.value.takeIf { it.isNotBlank() }
            ?: _pendingText.value?.takeIf { it.isNotBlank() }
            ?: return false
        if (_sendRequests.subscriptionCount.value == 0) return false
        // Only clear the draft after the request is accepted; a full buffer
        // must not silently discard what the user typed.
        if (!_sendRequests.tryEmit(payload)) return false
        _pendingText.value = null
        _currentInput.value = ""
        _clearInput.value += 1
        return true
    }

    fun publish(busy: Boolean, messages: List<AgentMessage>) {
        _snapshot.value = Snapshot(busy, messages)
    }

    suspend fun awaitIdle(timeoutMs: Long): Snapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (_snapshot.value.busy && System.currentTimeMillis() < deadline) {
            delay(200)
        }
        return _snapshot.value
    }

    suspend fun awaitCompletion(timeoutMs: Long, baselineMessages: Int = -1): Snapshot {
        val deadline = System.currentTimeMillis() + timeoutMs
        var started = _snapshot.value.busy
        while (System.currentTimeMillis() < deadline) {
            val current = _snapshot.value
            if (current.busy) started = true
            if (started && !current.busy) return current
            if (!started && baselineMessages >= 0 &&
                current.messages.size > baselineMessages
            ) {
                return current
            }
            delay(200)
        }
        return _snapshot.value
    }
}
