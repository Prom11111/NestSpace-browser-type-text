package com.coke.nestspace.ui.settings

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.coke.nestspace.R
import com.coke.nestspace.i18n.AppLanguage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AboutDetailScreen() {
    val context = LocalContext.current
    val versionName = rememberAppVersion()
    val scope = rememberCoroutineScope()
    var savingQr by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("交流与反馈", "Community")) }
        item {
            SettingsCard(
                title = "Telegram",
                status = AppLanguage.tr("加入", "Join")
            ) {
                Text(
                    "https://t.me/+JClG9_DojaM0ZGE1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { openTelegram(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppLanguage.tr("打开 Telegram 交流群", "Open Telegram group"))
                }
            }
        }
        item {
            SettingsCard(
                title = AppLanguage.tr("微信群", "WeChat group"),
                status = AppLanguage.tr("扫码加入", "Scan to join")
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.wechat_group),
                        contentDescription = AppLanguage.tr(
                            "NestSpace 微信群二维码",
                            "NestSpace WeChat group QR code"
                        ),
                        modifier = Modifier.size(240.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Button(
                    onClick = {
                        if (savingQr) return@Button
                        savingQr = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { saveWechatQr(context) }
                            }
                            savingQr = false
                            result.onSuccess { location ->
                                Toast.makeText(
                                    context,
                                    AppLanguage.tr(
                                        "二维码已保存到 $location",
                                        "QR code saved to $location"
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    AppLanguage.tr(
                                        "保存失败：${error.message ?: "未知错误"}",
                                        "Save failed: ${error.message ?: "unknown error"}"
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !savingQr
                ) {
                    Text(
                        if (savingQr) {
                            AppLanguage.tr("正在保存…", "Saving…")
                        } else {
                            AppLanguage.tr("保存二维码", "Save QR code")
                        }
                    )
                }
            }
        }
        item { SettingsSection(AppLanguage.tr("版本", "Version")) }
        item {
            SettingsCard(title = "NestSpace", status = versionName) {
                Text(
                    AppLanguage.tr(
                    "应用虚拟空间 + 纯净桌面 + 本地/远程 Agent。",
                    "App virtualization + a clean desktop + local/remote agents."
                ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    AppLanguage.tr(
                    "支持多开、独立实例数据与进程级隔离；本地 Agent 支持 Claude 与 " +
                        "OpenAI 兼容接口。",
                    "Supports multiple instances, isolated app data and " +
                        "process-level isolation. Local agents work with Claude and " +
                        "OpenAI-compatible APIs."
                ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun saveWechatQr(context: Context): String {
    val name = "NestSpace-WeChat-Group-${
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
    }.png"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/NestSpace"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create image in Pictures")
        try {
            context.resources.openRawResource(R.drawable.wechat_group).use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                    ?: error("Cannot open image output")
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Pictures/NestSpace/$name"
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    } else {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
            "NestSpace"
        ).apply { mkdirs() }
        val target = File(directory, name)
        context.resources.openRawResource(R.drawable.wechat_group).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.absolutePath
    }
}

private fun openTelegram(context: Context) {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://t.me/+JClG9_DojaM0ZGE1")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(
            context,
            AppLanguage.tr("无法打开 Telegram", "Could not open Telegram"),
            Toast.LENGTH_SHORT
        ).show()
    }
}
