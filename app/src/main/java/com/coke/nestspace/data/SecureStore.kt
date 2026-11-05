package com.coke.nestspace.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Small encrypted text-file helper for local user data (memory and skills).
 * Uses the existing Keystore-backed cipher and writes atomically.
 */
internal object SecureStore {

    private const val TAG = "SecureStore"

    fun read(context: Context, name: String): String? {
        val file = File(context.applicationContext.filesDir, name)
        if (!file.isFile) return null
        return runCatching {
            val stored = file.readText()
            if (stored.startsWith("enc:")) ApiKeyCipher.decrypt(stored) else stored
        }.onFailure {
            Log.w(TAG, "Cannot read $name", it)
        }.getOrNull()
    }

    fun write(context: Context, name: String, text: String): Boolean {
        val file = File(context.applicationContext.filesDir, name)
        val encrypted = ApiKeyCipher.encrypt(text)
        if (encrypted.isBlank()) {
            Log.w(TAG, "Cannot encrypt $name")
            return false
        }
        return runCatching {
            val temp = File(file.parentFile, "$name.tmp")
            temp.writeText(encrypted)
            if (!temp.renameTo(file)) {
                file.writeText(encrypted)
                temp.delete()
            }
            true
        }.onFailure {
            Log.w(TAG, "Cannot write $name", it)
        }.getOrDefault(false)
    }
}
