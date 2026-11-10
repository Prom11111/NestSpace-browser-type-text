package com.coke.nestspace.engine

import java.io.File
import java.security.MessageDigest

/** Compares an imported payload with the immutable copy already in a space. */
internal object ApkPayloadIdentity {
    fun same(pending: List<File>, installed: List<File>): Boolean {
        if (pending.size != installed.size) return false
        if (pending.any { !it.isFile } || installed.any { !it.isFile }) return false
        return pending.map(::sha256).sorted() == installed.map(::sha256).sorted()
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
