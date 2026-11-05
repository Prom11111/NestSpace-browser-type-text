package com.coke.nestspace.container

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.coke.nestspace.control.ContainerUiController
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Control endpoint living in a container process, where the target Activity
 * and its view hierarchy actually exist. Bound by the main process, which
 * hosts the MCP server and the local Agent.
 */
open class ContainerUiService : Service() {

    private val binder = object : IContainerUi.Stub() {
        override fun isAvailable(): Boolean = ContainerUiController.isAvailable()

        override fun observeJson(): String? =
            runBlocking { ContainerUiController.snapshotJson() }

        override fun screenshotJpegBase64(): String? =
            runBlocking { ContainerUiController.screenshotJpegBase64() }

        override fun screenshotPngPath(): String? {
            val bytes = runBlocking { ContainerUiController.screenshotBytes() } ?: return null
            val dir = File(cacheDir, "container-ui").apply { mkdirs() }
            val file = File(dir, "screenshot.png")
            return runCatching {
                file.writeBytes(bytes)
                file.absolutePath
            }.getOrNull()
        }

        override fun tapNode(id: String): Boolean =
            runBlocking { ContainerUiController.tapNode(id) }

        override fun tapText(text: String): Boolean =
            runBlocking { ContainerUiController.tapText(text) }

        override fun tapAt(x: Int, y: Int): Boolean =
            runBlocking { ContainerUiController.tap(x, y) }

        override fun setText(id: String, text: String): Boolean =
            runBlocking { ContainerUiController.setText(id, text) }

        override fun waitForText(text: String, timeoutMs: Long): Boolean =
            runBlocking { ContainerUiController.waitFor(text, timeoutMs) }

        override fun pressBack(): Boolean =
            runBlocking { ContainerUiController.pressBack() }

        override fun bringToFront(): Boolean =
            runBlocking { ContainerUiController.bringToFront() }

        override fun isMirrorMode(): Boolean = ContainerUiController.isMirrorMode()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

class ContainerUiService1 : ContainerUiService()

class ContainerUiService2 : ContainerUiService()

class ContainerUiService3 : ContainerUiService()

class ContainerUiService4 : ContainerUiService()

class ContainerUiService5 : ContainerUiService()

class ContainerUiService6 : ContainerUiService()

class ContainerUiService7 : ContainerUiService()

class ContainerUiService8 : ContainerUiService()

class ContainerUiService9 : ContainerUiService()

class ContainerUiService10 : ContainerUiService()

class ContainerUiService11 : ContainerUiService()

class ContainerUiService12 : ContainerUiService()

class ContainerUiService13 : ContainerUiService()

class ContainerUiService14 : ContainerUiService()

class ContainerUiService15 : ContainerUiService()

class ContainerUiService16 : ContainerUiService()
