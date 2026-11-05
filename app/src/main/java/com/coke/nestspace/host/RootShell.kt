package com.coke.nestspace.host

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

enum class RootStatus {
    AVAILABLE,
    DENIED,
    UNAVAILABLE
}

data class RootResult(
    val ok: Boolean,
    val output: String,
    val exitCode: Int = -1,
    val error: String? = null
)

/**
 * Runs one-shot commands through `su -c`.
 *
 * Root is only used after the user enables Host control in settings. Commands
 * are built from validated values inside [HostUiController]; no raw model
 * output is ever passed to the shell.
 */
object RootShell {

    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val CACHE_MS = 30_000L

    @Volatile
    private var cachedStatus: RootStatus? = null

    @Volatile
    private var cachedAt = 0L

    suspend fun status(force: Boolean = false): RootStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedStatus
        if (!force && cached != null && now - cachedAt < CACHE_MS) {
            return@withContext cached
        }
        // The first su call may show a grant prompt; allow time for the user.
        val result = runProcess(listOf("su", "-c", "id"), 15_000L)
        val status = when {
            result.ok && result.output.contains("uid=0") -> RootStatus.AVAILABLE
            result.output.contains("permission denied", ignoreCase = true) -> RootStatus.DENIED
            else -> RootStatus.UNAVAILABLE
        }
        cachedStatus = status
        cachedAt = now
        status
    }

    suspend fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): RootResult =
        withContext(Dispatchers.IO) { runProcess(listOf("su", "-c", command), timeoutMs) }

    fun invalidate() {
        cachedStatus = null
        cachedAt = 0L
    }

    private fun runProcess(command: List<String>, timeoutMs: Long): RootResult {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return RootResult(
                ok = false,
                output = "",
                error = t.message ?: "cannot start su"
            )
        }
        val buffer = ByteArrayOutputStream()
        val reader = Thread {
            try {
                process.inputStream.use { input -> input.copyTo(buffer) }
            } catch (_: Throwable) {
                // The process may be destroyed while the stream is being read.
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                val deadline = System.currentTimeMillis() + timeoutMs
                var done = false
                while (System.currentTimeMillis() < deadline) {
                    try {
                        process.exitValue()
                        done = true
                        break
                    } catch (_: IllegalThreadStateException) {
                        Thread.sleep(50L)
                    }
                }
                done
            }
        } catch (_: Throwable) {
            false
        }

        if (!finished) {
            process.destroyForcibly()
            reader.join(1_000L)
            return RootResult(
                ok = false,
                output = String(buffer.toByteArray(), Charsets.UTF_8),
                error = "root command timed out"
            )
        }

        reader.join(1_000L)
        val exitCode = try {
            process.exitValue()
        } catch (_: Throwable) {
            -1
        }
        val output = String(buffer.toByteArray(), Charsets.UTF_8)
        return RootResult(
            ok = exitCode == 0,
            output = output,
            exitCode = exitCode,
            error = if (exitCode == 0) null else "exit code $exitCode"
        )
    }
}
