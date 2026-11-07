package com.coke.nestspace.assistant

/**
 * Vosk often emits Chinese as space-separated tokens. Remove those visual
 * gaps without changing spaces between ordinary Latin words.
 */
internal fun normalizeSpeechText(raw: String): String {
    val han = "\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF"
    val punctuation = "，。！？；：、（）【】《》"
    return raw
        .trim()
        .replace(Regex("[\\s\\u3000]+"), " ")
        .replace(Regex("(?<=[$han])\\s+(?=[$han])"), "")
        .replace(Regex("(?<=[$han])\\s+(?=[$punctuation])"), "")
        .replace(Regex("(?<=[$punctuation])\\s+(?=[$han])"), "")
        .replace(Regex("(?<=[$han])\\s+(?=\\d)"), "")
        .replace(Regex("(?<=\\d)\\s+(?=[$han])"), "")
}
