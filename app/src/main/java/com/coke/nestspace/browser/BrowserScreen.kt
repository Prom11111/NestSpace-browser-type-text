package com.coke.nestspace.browser

import com.coke.nestspace.i18n.AppLanguage
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.ui.agent.DownloadProgressCard
import java.io.File

private const val HOME_URL = BrowserSession.HOME_URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    initialUrl: String?,
    initialTabId: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tabs by BrowserSession.tabsFlow.collectAsState()
    val downloads by BrowserSession.downloadsFlow.collectAsState()
    val takeover by UserGateManager.takeover.collectAsState()
    val browserTakeover = takeover?.takeIf {
        it.target == UserGateManager.TakeoverTarget.BROWSER
    }
    val proxyStatus by BrowserProxyManager.status.collectAsState()
    var showProxyDialog by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    // Session state lives in BrowserSession, not in the saved instance. Reset
    // this on recreation so a stale "initialized" value cannot close a fresh
    // activity before the first tab is attached.
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (BrowserSession.tabCount() == 0) {
            BrowserSession.open(
                context = context,
                url = initialUrl ?: HOME_URL,
                headless = false,
                newTab = true,
                showActivity = false
            )
        } else {
            val id = initialTabId
                ?: BrowserSession.selectedTabId()
                ?: BrowserSession.activeTabId()
            if (id != null) BrowserSession.selectTab(id)
        }
        initialized = true
    }

    LaunchedEffect(tabs.isEmpty(), initialized) {
        // The tab flow can lag behind the session's synchronous open() call.
        // Only close the activity when the session itself is empty, otherwise
        // a cold start finishes before the first tab reaches composition.
        if (initialized && tabs.isEmpty() && BrowserSession.tabCount() == 0) onBack()
    }

    val selected = tabs.firstOrNull { it.selected }
        ?: tabs.firstOrNull { it.active }
        ?: tabs.firstOrNull()

    var address by remember(selected?.id) {
        mutableStateOf(selected?.url ?: HOME_URL)
    }
    var editing by remember(selected?.id) { mutableStateOf(false) }
    LaunchedEffect(selected?.id, selected?.url, editing) {
        if (!editing) address = selected?.url ?: HOME_URL
    }

    fun openAddress() {
        val tab = selected ?: return
        val input = address.trim()
        if (input.isBlank()) return
        editing = false
        BrowserSession.navigate(BrowserSession.normalizeInput(input), tab.id)
    }

    BackHandler {
        val tab = selected
        if (tab != null && tab.canGoBack) {
            BrowserSession.back(tab.id)
        } else {
            onBack()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            BrowserHeader(
                tabCount = tabs.size,
                proxyEnabled = proxyStatus.applied,
                downloadCount = downloads.size,
                downloading = downloads.any { it.state == "downloading" },
                onBack = onBack,
                onOpenDownloads = { showDownloads = true },
                onOpenProxy = { showProxyDialog = true }
            )
            TabStrip(
                tabs = tabs,
                onSelect = { BrowserSession.selectTab(it) },
                onClose = { BrowserSession.closeTab(it) },
                canAddTab = tabs.size < BrowserSession.MAX_TABS,
                onNewTab = {
                    runCatching {
                        BrowserSession.open(
                            context = context,
                            url = HOME_URL,
                            headless = false,
                            newTab = true,
                            showActivity = false
                        )
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            error.message ?: error.javaClass.simpleName,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
            AddressBar(
                address = address,
                onAddressChange = {
                    editing = true
                    address = it
                },
                onOpen = { openAddress() },
                onEditingChange = { if (!it) editing = false },
                canGoBack = selected?.canGoBack == true,
                canGoForward = selected?.canGoForward == true,
                onBack = { selected?.let { BrowserSession.back(it.id) } },
                onForward = { selected?.let { BrowserSession.forward(it.id) } },
                onReload = { selected?.let { BrowserSession.reload(it.id) } }
            )
            if (selected?.loading == true) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(2.dp))
            }
            browserTakeover?.let { request ->
                BrowserTakeoverBanner(
                    title = request.title,
                    detail = request.detail,
                    onDone = {
                        UserGateManager.completeTakeover(
                            request.id,
                            UserGateManager.TakeoverResult.COMPLETED
                        )
                    },
                    onSkip = {
                        UserGateManager.completeTakeover(
                            request.id,
                            UserGateManager.TakeoverResult.SKIPPED
                        )
                    }
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                if (selected != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext -> FrameLayout(viewContext) },
                        update = { container ->
                            val tab = selected
                            val view = BrowserSession.ensureVisibleWebView(
                                container.context,
                                tab.id
                            ) ?: return@AndroidView
                            if (container.childCount != 1 ||
                                container.getChildAt(0) !== view
                            ) {
                                while (container.childCount > 0) {
                                    container.removeViewAt(0)
                                }
                                (view.parent as? ViewGroup)?.removeView(view)
                                container.addView(
                                    view,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            AppLanguage.tr("没有打开的标签页", "No open tabs"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    if (showProxyDialog) {
        BrowserProxyDialog(onDismiss = { showProxyDialog = false })
    }
    if (showDownloads) {
        DownloadMenu(
            downloads = downloads,
            onDismiss = { showDownloads = false },
            onOpen = { openDownloadedFile(context, it) },
            onDelete = { BrowserSession.deleteDownload(context, it.id) }
        )
    }
}

@Composable
private fun DownloadMenu(
    downloads: List<BrowserSession.DownloadState>,
    onDismiss: () -> Unit,
    onOpen: (BrowserSession.DownloadState) -> Unit,
    onDelete: (BrowserSession.DownloadState) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<BrowserSession.DownloadState?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppLanguage.tr("下载", "Downloads")) },
        text = {
            if (downloads.isEmpty()) {
                Text(
                    AppLanguage.tr("还没有下载文件", "No downloads yet"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(downloads, key = { it.id }) { download ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            DownloadProgressCard(
                                download = download,
                                modifier = Modifier.weight(1f),
                                onClick = download.state.takeIf { it == "done" }?.let {
                                    { onOpen(download) }
                                }
                            )
                            IconButton(
                                onClick = { pendingDelete = download },
                                enabled = download.state != "downloading"
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = AppLanguage.tr("删除下载", "Delete download"),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppLanguage.tr("关闭", "Close"))
            }
        }
    )
    pendingDelete?.let { download ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(AppLanguage.tr("删除下载？", "Delete download?")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "将从列表移除并删除文件：${download.name}",
                        "Remove from the list and delete the file: ${download.name}"
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(download)
                    }
                ) {
                    Text(
                        AppLanguage.tr("删除", "Delete"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

private fun openDownloadedFile(
    context: android.content.Context,
    download: BrowserSession.DownloadState
) {
    if (download.state != "done") return
    val uri = runCatching {
        val stored = download.uri?.let(Uri::parse)
        when (stored?.scheme?.lowercase()) {
            "content" -> stored
            "file" -> stored.path?.let { path ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    File(path)
                )
            }
            else -> download.path?.let { path ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    File(path)
                )
            }
        }
    }.getOrNull()
    if (uri == null) {
        Toast.makeText(
            context,
            AppLanguage.tr("找不到下载的文件", "Downloaded file was not found"),
            Toast.LENGTH_SHORT
        ).show()
        return
    }
    val extension = download.name.substringAfterLast('.', "").lowercase()
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: if (extension == "apk") "application/vnd.android.package-archive" else "*/*"
    if (mime.equals("text/html", true) ||
        mime.equals("application/xhtml+xml", true) ||
        mime.equals("image/svg+xml", true)
    ) {
        runCatching { BrowserSession.openLocalContent(context, uri) }.onFailure {
            Toast.makeText(
                context,
                AppLanguage.tr("无法在自带浏览器中打开这个文件", "Could not open this file in the browser"),
                Toast.LENGTH_SHORT
            ).show()
        }
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(
            context,
            AppLanguage.tr("无法打开这个文件", "Could not open this file"),
            Toast.LENGTH_SHORT
        ).show()
    }
}

@Composable
private fun BrowserHeader(
    tabCount: Int,
    proxyEnabled: Boolean,
    downloadCount: Int,
    downloading: Boolean,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenProxy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppLanguage.tr("返回桌面", "Back to desktop"))
        }
        Text(
            text = AppLanguage.tr("浏览器", "Browser"),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Box {
            IconButton(onClick = onOpenDownloads) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = AppLanguage.tr("下载", "Downloads"),
                    tint = if (downloading) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (downloadCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 7.dp, end = 7.dp)
                        .size(7.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                )
            }
        }
        IconButton(onClick = onOpenProxy) {
            Icon(
                Icons.Default.Settings,
                contentDescription = AppLanguage.tr("代理设置", "Proxy settings"),
                tint = if (proxyEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Text(
            text = AppLanguage.trCount(
                    tabCount,
                    if (tabCount == 1) "1 个标签页" else "$tabCount 个标签页",
                    "1 tab",
                    "$tabCount tabs"
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TabStrip(
    tabs: List<BrowserSession.Tab>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    canAddTab: Boolean,
    onNewTab: () -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(tabs, key = { it.id }) { tab ->
            TabChip(
                tab = tab,
                onSelect = { onSelect(tab.id) },
                onClose = { onClose(tab.id) }
            )
        }
        item(key = "new-tab") {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                IconButton(
                    onClick = onNewTab,
                    enabled = canAddTab,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = AppLanguage.tr("新建标签页", "New tab"),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabChip(
    tab: BrowserSession.Tab,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val title = tab.title.takeIf { it.isNotBlank() }
        ?: runCatching { Uri.parse(tab.url).host }.getOrNull()
        ?: AppLanguage.tr("新标签页", "New tab")
    val selected = tab.selected
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        }
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onSelect)
                .padding(start = 12.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(min = 48.dp, max = 148.dp)
            )
            if (tab.loading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(12.dp),
                    strokeWidth = 1.5.dp
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = AppLanguage.tr("关闭标签页", "Close tab"),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * Non-blocking banner for a browser takeover. The user can operate the page
 * behind it (for example drag a slider), then tap Done to resume the agent.
 */
@Composable
private fun BrowserTakeoverBanner(
    title: String,
    detail: String,
    onDone: () -> Unit,
    onSkip: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onDone) {
                Text(AppLanguage.tr("完成", "Done"))
            }
            TextButton(onClick = onSkip) {
                Text(AppLanguage.tr("跳过", "Skip"))
            }
        }
    }
}

@Composable
private fun AddressBar(
    address: String,
    onAddressChange: (String) -> Unit,
    onOpen: () -> Unit,
    onEditingChange: (Boolean) -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun open() {
        keyboard?.hide()
        focusManager.clearFocus()
        onOpen()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = AppLanguage.tr("后退", "Back"))
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = AppLanguage.tr("前进", "Forward"))
        }
        IconButton(onClick = onReload) {
            Icon(Icons.Default.Refresh, contentDescription = AppLanguage.tr("刷新", "Refresh"))
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                BasicTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (!it.isFocused) onEditingChange(false) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { open() }),
                    decorationBox = { innerTextField ->
                        if (address.isBlank()) {
                            Text(
                                AppLanguage.tr("搜索或输入网址", "Search or enter address"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                )
                IconButton(
                    onClick = { open() },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = AppLanguage.tr("打开", "Open"),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}
