package com.coke.nestspace.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

class WallpaperStore(private val context: Context) {

    private val dir = File(context.filesDir, "desktop")
    private val file = File(dir, "wallpaper.img")

    fun load(): ImageBitmap? {
        if (!file.isFile) return null
        return decode(file)
    }

    fun save(uri: Uri): ImageBitmap? {
        return runCatching {
            dir.mkdirs()
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
            input.use { from ->
                file.outputStream().use { to -> from.copyTo(to) }
            }
            val bitmap = decode(file)
            if (bitmap == null) file.delete()
            bitmap
        }.getOrNull()
    }

    /** Used by the MCP settings API, which receives the image as base64. */
    fun saveBytes(bytes: ByteArray): Boolean = runCatching {
        dir.mkdirs()
        file.writeBytes(bytes)
        val bitmap = decode(file)
        if (bitmap == null) {
            file.delete()
            false
        } else {
            true
        }
    }.getOrDefault(false)

    fun exists(): Boolean = file.isFile

    fun clear() {
        file.delete()
    }

    private fun decode(file: File): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    }.getOrNull()
}
