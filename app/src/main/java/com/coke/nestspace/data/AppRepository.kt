package com.coke.nestspace.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    suspend fun listLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, 0)

        resolved.asSequence()
            .filter { it.activityInfo?.applicationInfo?.packageName != context.packageName }
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .map { ai ->
                val label = runCatching { ai.loadLabel(pm).toString() }
                    .getOrDefault(ai.packageName)
                val icon = runCatching {
                    ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                }.getOrNull()
                AppInfo(
                    packageName = ai.packageName,
                    label = label,
                    icon = icon,
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    apkPaths = listOfNotNull(
                        ai.publicSourceDir ?: ai.sourceDir,
                        *(ai.splitPublicSourceDirs ?: ai.splitSourceDirs ?: emptyArray())
                    ).filter { it.isNotBlank() }.distinct()
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
