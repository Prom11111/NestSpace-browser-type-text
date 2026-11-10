package com.coke.nestspace.runtime

import android.content.Intent

fun webUrlFromViewIntent(intent: Intent): String? =
    intent.dataString
        ?.takeIf {
            intent.action == Intent.ACTION_VIEW &&
                (it.startsWith("http://") || it.startsWith("https://"))
        }
