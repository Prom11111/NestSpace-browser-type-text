package com.coke.nestspace.ui.plugins

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.coke.nestspace.browser.BrowserActivity
import com.coke.nestspace.R
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.plugin.AgentModelPlugins
import com.coke.nestspace.plugin.AgentPlugins
import com.coke.nestspace.plugin.AgentToolHookPlugins
import com.coke.nestspace.plugin.AgentToolPlugins
import com.coke.nestspace.plugin.AgentToolPresentationPlugins
import com.coke.nestspace.plugin.AndroidPluginSecrets
import com.coke.nestspace.plugin.AndroidPluginSettings
import com.coke.nestspace.plugin.BuiltInPluginEntry
import com.coke.nestspace.plugin.PluginCapability
import com.coke.nestspace.plugin.PluginCenterStore
import com.coke.nestspace.plugin.PluginCommandPlugins
import com.coke.nestspace.plugin.PluginDescriptor
import com.coke.nestspace.plugin.PluginLlmProviders
import com.coke.nestspace.plugin.PluginManifest
import com.coke.nestspace.plugin.PluginPackageInstaller
import com.coke.nestspace.plugin.PluginPermission
import com.coke.nestspace.plugin.PluginSourceRegistry
import com.coke.nestspace.plugin.PluginSourceLoader
import com.coke.nestspace.plugin.PluginSettingsField
import com.coke.nestspace.plugin.PluginSettingsField.Format
import com.coke.nestspace.plugin.PluginSettingView
import com.coke.nestspace.plugin.PluginSpeechProviders
import com.coke.nestspace.plugin.PluginUiPlugins
import com.coke.nestspace.plugin.RuntimeProxyPlugins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PluginDetail(
    val id: String,
    val title: String,
    val description: String,
    val version: String,
    val publisher: String,
    val capabilities: Set<PluginCapability>,
    val iconFile: java.io.File? = null,
    val htmlFile: java.io.File? = null,
    val builtin: BuiltInPluginEntry? = null
)

private enum class PluginEntryKind {
    PACKAGE,
    SOURCE,
    BUILTIN,
    REGISTERED
}

private data class PluginActionTarget(
    val id: String,
    val defaultName: String,
    val displayName: String,
    val kind: PluginEntryKind
)

private data class PluginMenuAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

private data class PluginSettingsTarget(
    val id: String,
    val title: String,
    val view: PluginSettingView,
    val values: Map<String, String>,
    val configuredSecrets: Set<String>
)

private enum class PluginCategory {
    ALL,
    AGENT,
    DISPLAY,
    NETWORK,
    DATA,
    RUNTIME,
    SYSTEM
}

@Composable
internal fun PluginCenterScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRevision by AppSettingsEvents.revision.collectAsState()
    var localRevision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PluginCategory.ALL) }
    var installStatus by remember { mutableStateOf("") }
    var installing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PluginDetail?>(null) }
    var renameTarget by remember { mutableStateOf<PluginActionTarget?>(null) }
    var deleteTarget by remember { mutableStateOf<PluginActionTarget?>(null) }
    var exportTarget by remember { mutableStateOf<PluginManifest?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var settingsTarget by remember { mutableStateOf<PluginSettingsTarget?>(null) }
    var settingsSaving by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf("") }

    val builtIns = remember(context, settingsRevision, localRevision) {
        PluginCenterStore.builtIns(context)
    }
    val packages = remember(context, localRevision) {
        PluginPackageInstaller.get(context).installedManifests()
    }
    val sourcePlugins = remember(context, localRevision) {
        PluginSourceRegistry.manifests()
    }
    val packageIds = packages.mapTo(HashSet()) { it.id }
    val sourceIds = sourcePlugins.mapTo(HashSet()) { it.id }
    val builtInIds = builtIns.mapTo(HashSet()) { it.descriptor.id }
    val extraRegistered = remember(settingsRevision, localRevision) {
        registeredPlugins().filterNot {
            it.id in builtInIds || it.id in packageIds || it.id in sourceIds
        }
    }
    val installedCount =
        packages.size + sourcePlugins.size + extraRegistered.size
    val visiblePackages = packages.filter { manifest ->
        manifest.matches(
            query,
            category,
            PluginCenterStore.displayName(
                context,
                manifest.id,
                manifest.name
            )
        )
    }
    val visibleSource = sourcePlugins.filter { manifest ->
        manifest.matches(
            query,
            category,
            PluginCenterStore.displayName(
                context,
                manifest.id,
                manifest.name
            )
        )
    }
    val visibleRegistered = extraRegistered.filter {
        it.matches(
            query,
            category,
            "",
            PluginCenterStore.displayName(context, it.id, it.name)
        )
    }

    val packagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        installing = true
        installStatus = AppLanguage.tr("正在安装…", "Installing…")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PluginPackageInstaller.get(context).installFromUri(uri)
                }
            }.onSuccess { manifest ->
                installStatus = AppLanguage.tr(
                    "已安装 ${manifest.name} ${manifest.version}",
                    "Installed ${manifest.name} ${manifest.version}"
                )
                localRevision++
                AppSettingsEvents.notifyChanged()
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "安装失败：${it.message ?: it.javaClass.simpleName}",
                    "Install failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }
            installing = false
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/octet-stream"
        )
    ) { uri ->
        val manifest = exportTarget
        exportTarget = null
        if (uri == null || manifest == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (PluginSourceRegistry.contains(manifest.id)) {
                        PluginSourceLoader.exportToUri(
                            context,
                            manifest.id,
                            uri
                        )
                    } else {
                        PluginPackageInstaller.get(context)
                            .exportToUri(manifest.id, uri)
                    }
                }
            }.onSuccess {
                installStatus = AppLanguage.tr(
                    "已导出 ${manifest.name}",
                    "Exported ${manifest.name}"
                )
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "导出失败：${it.message ?: it.javaClass.simpleName}",
                    "Export failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    fun sharePackage(manifest: PluginManifest) {
        scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    if (PluginSourceRegistry.contains(manifest.id)) {
                        PluginSourceLoader.createShareFile(
                            context,
                            manifest.id
                        )
                    } else {
                        PluginPackageInstaller.get(context)
                            .createShareFile(manifest.id)
                    }
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    file
                )
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .apply {
                        clipData = ClipData.newRawUri(file.name, uri)
                    }
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(
                    Intent.createChooser(
                        send,
                        AppLanguage.tr("分享插件", "Share plugin")
                    )
                )
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "分享失败：${it.message ?: it.javaClass.simpleName}",
                    "Share failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    fun openPluginSettings(
        manifest: PluginManifest,
        sourcePlugin: Boolean
    ) {
        scope.launch {
            settingsError = ""
            runCatching {
                val schemaFile = withContext(Dispatchers.IO) {
                    val file = if (sourcePlugin) {
                        PluginSourceRegistry.settingsSchemaFile(manifest.id)
                    } else {
                        PluginPackageInstaller.get(context)
                            .settingsSchemaFile(manifest.id)
                    }
                    requireNotNull(file) {
                        "Plugin does not provide a settings schema"
                    }
                }
                val view = withContext(Dispatchers.IO) {
                    PluginSettingView.importSchema(schemaFile.readText())
                }
                val loaded = withContext(Dispatchers.IO) {
                    PluginSettingFormStore(
                        AndroidPluginSettings(context, manifest.id),
                        AndroidPluginSecrets(context, manifest.id)
                    ).load(view)
                }
                PluginSettingsTarget(
                    id = manifest.id,
                    title = PluginCenterStore.displayName(
                        context,
                        manifest.id,
                        manifest.name
                    ),
                    view = view,
                    values = loaded.values,
                    configuredSecrets = loaded.configuredSecrets
                )
            }.onSuccess {
                settingsTarget = it
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "无法打开设置：${it.message ?: it.javaClass.simpleName}",
                    "Could not open settings: " +
                        (it.message ?: it.javaClass.simpleName)
                )
            }
        }
    }

    fun savePluginSettings(
        target: PluginSettingsTarget,
        draft: PluginSettingDraft
    ) {
        settingsSaving = true
        settingsError = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PluginSettingFormStore(
                        AndroidPluginSettings(context, target.id),
                        AndroidPluginSecrets(context, target.id)
                    ).save(target.view, draft)
                }
            }.onSuccess {
                settingsSaving = false
                settingsTarget = null
                installStatus = AppLanguage.tr(
                    "已保存 ${target.title} 的设置",
                    "Saved settings for ${target.title}"
                )
                AppSettingsEvents.notifyChanged()
            }.onFailure {
                settingsSaving = false
                settingsError = it.message ?: it.javaClass.simpleName
            }
        }
    }

    fun actionTarget(
        id: String,
        defaultName: String,
        kind: PluginEntryKind
    ) = PluginActionTarget(
        id = id,
        defaultName = defaultName,
        displayName = PluginCenterStore.displayName(
            context,
            id,
            defaultName
        ),
        kind = kind
    )

    fun renameAction(target: PluginActionTarget) = PluginMenuAction(
        AppLanguage.tr("重命名", "Rename"),
        Icons.Rounded.Edit
    ) {
        renameTarget = target
    }

    fun settingsAction(
        manifest: PluginManifest,
        sourcePlugin: Boolean
    ) = PluginMenuAction(
        AppLanguage.tr("设置", "Settings"),
        Icons.Rounded.Settings
    ) {
        openPluginSettings(manifest, sourcePlugin)
    }

    fun toggleAction(
        id: String,
        displayName: String,
        enabled: Boolean
    ) = PluginMenuAction(
        AppLanguage.tr(
            if (enabled) "停用" else "启用",
            if (enabled) "Disable" else "Enable"
        ),
        if (enabled) {
            Icons.Rounded.PauseCircleOutline
        } else {
            Icons.Rounded.PlayCircleOutline
        }
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    PluginCenterStore.setEnabled(context, id, !enabled)
                }
            }.onSuccess {
                localRevision++
                installStatus = AppLanguage.tr(
                    if (enabled) {
                        "已停用 $displayName"
                    } else {
                        "已启用 $displayName"
                    },
                    if (enabled) {
                        "Disabled $displayName"
                    } else {
                        "Enabled $displayName"
                    }
                )
            }.onFailure {
                installStatus = AppLanguage.tr(
                    "操作失败：${it.message ?: it.javaClass.simpleName}",
                    "Operation failed: ${it.message ?: it.javaClass.simpleName}"
                )
            }
        }
    }

    fun packageActions(manifest: PluginManifest): List<PluginMenuAction> {
        val target = actionTarget(
            manifest.id,
            manifest.name,
            PluginEntryKind.PACKAGE
        )
        val enabled = PluginCenterStore.isEnabled(context, manifest.id)
        return buildList {
            add(toggleAction(manifest.id, target.displayName, enabled))
            if (enabled && manifest.contributions.settingsPath.isNotBlank()) {
                add(settingsAction(manifest, sourcePlugin = false))
            }
            add(
                renameAction(target)
            )
            add(
                PluginMenuAction(
                    AppLanguage.tr("分享", "Share"),
                    Icons.Rounded.Share
                ) {
                    sharePackage(manifest)
                }
            )
            add(
                PluginMenuAction(
                    AppLanguage.tr("导出", "Export"),
                    Icons.Rounded.Download
                ) {
                    exportTarget = manifest
                    exportPicker.launch(manifest.exportFileName())
                }
            )
            add(
                PluginMenuAction(
                    AppLanguage.tr("删除", "Delete"),
                    Icons.Rounded.DeleteOutline,
                    destructive = true
                ) {
                    deleteTarget = target
                }
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            PluginInstalledHeader(
                installedCount = installedCount,
                installing = installing,
                installStatus = installStatus,
                onInstall = {
                    packagePicker.launch(
                        arrayOf(
                            "application/zip",
                            "application/octet-stream"
                        )
                    )
                }
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null
                    )
                },
                placeholder = {
                    Text(
                        AppLanguage.tr(
                            "搜索插件、能力或发布者",
                            "Search plugins, capabilities or publishers"
                        )
                    )
                }
            )
        }

        item {
            PluginCategoryRow(
                selected = category,
                onSelect = { category = it }
            )
        }

        if (visiblePackages.isEmpty() &&
            visibleSource.isEmpty() &&
            visibleRegistered.isEmpty()
        ) {
            item {
                EmptyPluginResults()
            }
        }
        if (visibleSource.isNotEmpty() || visiblePackages.isNotEmpty()) {
            item { PluginSectionTitle(AppLanguage.tr("已安装插件", "Installed plugins")) }
            item {
                PluginTileGrid {
                    visibleSource.forEach { manifest ->
                        val displayName = PluginCenterStore.displayName(
                            context,
                            manifest.id,
                            manifest.name
                        )
                        val enabled = PluginCenterStore.isEnabled(
                            context,
                            manifest.id
                        )
                        PluginTile(
                            title = displayName,
                            subtitle = AppLanguage.tr(
                                if (enabled) "已安装" else "已停用",
                                if (enabled) "Installed" else "Disabled"
                            ),
                            iconFile = PluginSourceRegistry
                                .iconFile(manifest.id),
                            fallbackIcon = Icons.Rounded.Extension,
                            badge = AppLanguage.tr("源码", "DEV"),
                            enabled = enabled,
                            onOpen = {
                                detail = manifest.toDetail(context)
                            },
                            menuActions = buildList {
                                add(
                                    toggleAction(
                                        manifest.id,
                                        displayName,
                                        enabled
                                    )
                                )
                                if (enabled &&
                                    manifest.contributions.settingsPath.isNotBlank()
                                ) {
                                    add(
                                        settingsAction(
                                            manifest,
                                            sourcePlugin = true
                                        )
                                    )
                                }
                                add(
                                    renameAction(
                                        actionTarget(
                                            manifest.id,
                                            manifest.name,
                                            PluginEntryKind.SOURCE
                                        )
                                    )
                                )
                                add(
                                    PluginMenuAction(
                                        AppLanguage.tr("分享", "Share"),
                                        Icons.Rounded.Share
                                    ) {
                                        sharePackage(manifest)
                                    }
                                )
                                add(
                                    PluginMenuAction(
                                        AppLanguage.tr("导出", "Export"),
                                        Icons.Rounded.Download
                                    ) {
                                        exportTarget = manifest
                                        exportPicker.launch(
                                            manifest.exportFileName()
                                        )
                                    }
                                )
                            }
                        )
                    }
                    visiblePackages.forEach { manifest ->
                        val displayName = PluginCenterStore.displayName(
                            context,
                            manifest.id,
                            manifest.name
                        )
                        val enabled = PluginCenterStore.isEnabled(
                            context,
                            manifest.id
                        )
                        PluginTile(
                            title = displayName,
                            subtitle = AppLanguage.tr(
                                if (enabled) "已安装" else "已停用",
                                if (enabled) "Installed" else "Disabled"
                            ),
                            iconFile = PluginPackageInstaller.get(context)
                                .iconFile(manifest.id),
                            fallbackIcon = Icons.Rounded.Extension,
                            enabled = enabled,
                            onOpen = {
                                detail = manifest.toDetail(context)
                            },
                            menuActions = packageActions(manifest)
                        )
                    }
                }
            }
        }

        if (visibleRegistered.isNotEmpty()) {
            item { PluginSectionTitle(AppLanguage.tr("其它已加载插件", "Other loaded plugins")) }
            item {
                PluginTileGrid {
                    visibleRegistered.forEach { descriptor ->
                        val displayName = PluginCenterStore.displayName(
                            context,
                            descriptor.id,
                            descriptor.name
                        )
                        PluginTile(
                            title = displayName,
                            subtitle = AppLanguage.tr("已加载", "Loaded"),
                            iconFile = null,
                            fallbackIcon = Icons.Rounded.Extension,
                            onOpen = {
                                detail = descriptor.toDetail(
                                    AppLanguage.tr(
                                        "当前进程已加载",
                                        "Loaded in the current process"
                                    ),
                                    AppLanguage.tr(
                                        "外部插件",
                                        "External plugin"
                                    ),
                                    title = displayName
                                )
                            },
                            menuActions = listOf(
                                renameAction(
                                    actionTarget(
                                        descriptor.id,
                                        descriptor.name,
                                        PluginEntryKind.REGISTERED
                                    )
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenamePluginDialog(
            target = target,
            onDismiss = { renameTarget = null },
            onSave = { name ->
                runCatching {
                    PluginCenterStore.setDisplayName(
                        context,
                        target.id,
                        name
                    )
                }.onSuccess {
                    localRevision++
                    if (detail?.id == target.id) {
                        detail = detail?.copy(
                            title = PluginCenterStore.displayName(
                                context,
                                target.id,
                                target.defaultName
                            )
                        )
                    }
                    installStatus = AppLanguage.tr(
                        "已重命名为 ${PluginCenterStore.displayName(
                            context,
                            target.id,
                            target.defaultName
                        )}",
                        "Renamed to ${PluginCenterStore.displayName(
                            context,
                            target.id,
                            target.defaultName
                        )}"
                    )
                    renameTarget = null
                }.onFailure {
                    installStatus = AppLanguage.tr(
                        "重命名失败：${it.message}",
                        "Rename failed: ${it.message}"
                    )
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!deleting) deleteTarget = null
            },
            title = {
                Text(AppLanguage.tr("删除插件", "Delete plugin"))
            },
            text = {
                Text(
                    AppLanguage.tr(
                        "将删除 ${target.displayName} 的安装包及插件私有存储、" +
                            "缓存、SQLite 数据库、设置和密钥。此操作无法撤销。",
                        "This deletes ${target.displayName}, including its package, " +
                            "private storage, cache, SQLite databases, settings " +
                            "and secrets. This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        deleteTarget = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    PluginPackageInstaller.get(context)
                                        .uninstall(target.id)
                                }
                            }.onSuccess {
                                if (detail?.id == target.id) detail = null
                                localRevision++
                                AppSettingsEvents.notifyChanged()
                                installStatus = AppLanguage.tr(
                                    "已删除 ${target.displayName}",
                                    "Deleted ${target.displayName}"
                                )
                            }.onFailure {
                                installStatus = AppLanguage.tr(
                                    "删除失败：${it.message ?: it.javaClass.simpleName}",
                                    "Delete failed: ${it.message ?: it.javaClass.simpleName}"
                                )
                            }
                            deleting = false
                        }
                    }
                ) {
                    Text(
                        AppLanguage.tr("删除", "Delete"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { deleteTarget = null }
                ) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    settingsTarget?.let { target ->
        PluginSettingsDialog(
            target = target,
            saving = settingsSaving,
            error = settingsError,
            onDismiss = {
                if (!settingsSaving) {
                    settingsTarget = null
                    settingsError = ""
                }
            },
            onSave = { draft ->
                savePluginSettings(target, draft)
            }
        )
    }

    detail?.let { value ->
        PluginDetailDialog(
            detail = value,
            onBuiltinEnabledChange = { enabled ->
                val builtin = value.builtin
                if (builtin != null) {
                    PluginCenterStore.setEnabled(
                        context,
                        builtin.descriptor.id,
                        enabled
                    )
                    localRevision++
                    detail = value.copy(
                        builtin = builtin.copy(enabled = enabled)
                    )
                }
            },
            onDismiss = { detail = null }
        )
    }
}

@Composable
private fun PluginInstalledHeader(
    installedCount: Int,
    installing: Boolean,
    installStatus: String,
    onInstall: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_plugin_center),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        AppLanguage.tr(
                            "$installedCount 个已安装",
                            "$installedCount installed"
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        AppLanguage.tr(
                            "插件参数、工具、界面与运行时代理",
                            "Plugin settings, tools, UI and runtime proxies"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                AppLanguage.tr(
                    "管理已安装插件的参数和能力。",
                    "Manage settings and capabilities of installed plugins."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onInstall, enabled = !installing) {
                    Icon(
                        Icons.Rounded.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (installing) {
                            AppLanguage.tr("正在安装…", "Installing…")
                        } else {
                            AppLanguage.tr("从文件安装", "Install from file")
                        }
                    )
                }
                if (installStatus.isNotBlank()) {
                    Text(
                        installStatus,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp)
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PluginTileGrid(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        content()
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PluginTile(
    title: String,
    subtitle: String,
    iconFile: java.io.File?,
    fallbackIcon: ImageVector,
    badge: String? = null,
    enabled: Boolean = true,
    onOpen: () -> Unit,
    menuActions: List<PluginMenuAction> = emptyList()
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val bitmap = remember(iconFile?.path) {
        iconFile?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path) }
            ?.asImageBitmap()
    }
    Box {
        Column(
            modifier = Modifier
                .width(92.dp)
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        if (menuActions.isNotEmpty()) {
                            menuExpanded = true
                        }
                    }
                )
                .padding(horizontal = 3.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .alpha(if (enabled) 1f else 0.5f),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        fallbackIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(30.dp)
                    )
                }
                if (!enabled) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            AppLanguage.tr("停用", "OFF"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onError,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
                if (!badge.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            menuActions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            tint = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        action.onClick()
                    }
                )
            }
        }
    }
}

@Composable
private fun PluginCategoryRow(
    selected: PluginCategory,
    onSelect: (PluginCategory) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(PluginCategory.entries, key = { it.name }) { item ->
            FilterChip(
                selected = selected == item,
                onClick = { onSelect(item) },
                label = { Text(categoryLabel(item)) }
            )
        }
    }
}

@Composable
private fun EmptyPluginResults() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                AppLanguage.tr(
                    "没有找到匹配的插件",
                    "No matching plugins found"
                ),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                AppLanguage.tr(
                    "尝试清空搜索或切换分类。",
                    "Try clearing search or changing category."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PluginCapabilitySummary(capabilities: Set<PluginCapability>) {
    if (capabilities.isEmpty()) return
    val permissions = PluginPermission.groupsFor(capabilities).toList()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        permissions.take(4).forEach { permission ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        permissionLabel(permission),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
        if (permissions.size > 4) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        "+${permissions.size - 4}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}

@Composable
private fun RenamePluginDialog(
    target: PluginActionTarget,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(target.id, target.displayName) {
        mutableStateOf(target.displayName)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(AppLanguage.tr("重命名插件", "Rename plugin"))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(target.defaultName)
                    }
                )
                Text(
                    AppLanguage.tr(
                        "留空会恢复默认名称。插件 ID 和包名不会改变。",
                        "Leave empty to restore the default name. " +
                            "The plugin ID and package name do not change."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) {
                Text(AppLanguage.tr("保存", "Save"))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) {
                    Text(AppLanguage.tr("恢复默认", "Reset"))
                }
                TextButton(onClick = onDismiss) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PluginSettingsDialog(
    target: PluginSettingsTarget,
    saving: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onSave: (PluginSettingDraft) -> Unit
) {
    val context = LocalContext.current
    val formStore = remember(target.id) {
        PluginSettingFormStore(
            AndroidPluginSettings(context, target.id),
            AndroidPluginSecrets(context, target.id)
        )
    }
    var values by remember(target.id, target.values) {
        mutableStateOf(target.values)
    }
    var secretsToClear by remember(target.id) {
        mutableStateOf(emptySet<String>())
    }
    var validationError by remember(target.id) {
        mutableStateOf("")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(target.view.title.ifBlank {
                    AppLanguage.tr("插件设置", "Plugin settings")
                })
                Text(
                    target.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (target.view.description.isNotBlank()) {
                    Text(
                        target.view.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (target.view.fields.isEmpty()) {
                    Text(
                        AppLanguage.tr(
                            "该插件没有可配置参数。",
                            "This plugin has no configurable settings."
                        )
                    )
                }
                target.view.fields.forEach { field ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            buildString {
                                append(field.title)
                                if (field.isRequired) append(" *")
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (field.description.isNotBlank()) {
                            Text(
                                field.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        when (field.type) {
                            PluginSettingsField.Type.BOOLEAN -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (values[field.id] == "true") {
                                            AppLanguage.tr("已启用", "Enabled")
                                        } else {
                                            AppLanguage.tr("已停用", "Disabled")
                                        },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Switch(
                                        checked = values[field.id] == "true",
                                        onCheckedChange = {
                                            values = values + (
                                                field.id to it.toString()
                                            )
                                        },
                                        enabled = !saving
                                    )
                                }
                            }
                            PluginSettingsField.Type.SELECT -> {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    field.options.forEach { option ->
                                        FilterChip(
                                            selected = values[field.id] == option,
                                            onClick = {
                                                values = values + (
                                                    field.id to option
                                                )
                                            },
                                            enabled = !saving,
                                            label = { Text(option) }
                                        )
                                    }
                                }
                            }
                            else -> {
                                val secretConfigured =
                                    field.id in target.configuredSecrets
                                OutlinedTextField(
                                    value = values[field.id].orEmpty(),
                                    onValueChange = {
                                        values = values + (field.id to it)
                                        if (it.isNotBlank()) {
                                            secretsToClear -= field.id
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !saving,
                                    singleLine = field.type !=
                                        PluginSettingsField.Type.TEXT,
                                    placeholder = {
                                        val hint = if (field.type ==
                                            PluginSettingsField.Type.SECRET
                                        ) {
                                            if (secretConfigured) {
                                                AppLanguage.tr(
                                                    "已保存，留空保持不变",
                                                    "Saved; leave blank to keep"
                                                )
                                            } else {
                                                field.placeholder
                                            }
                                        } else {
                                            field.placeholder
                                        }
                                        if (hint.isNotBlank()) {
                                            Text(
                                                hint
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = when {
                                            field.type ==
                                                PluginSettingsField.Type.INTEGER ->
                                                KeyboardType.Number
                                            field.format == Format.URL ||
                                                field.format == Format.EMAIL ->
                                                KeyboardType.Uri
                                            else -> KeyboardType.Text
                                        }
                                    )
                                )
                                if (field.type ==
                                    PluginSettingsField.Type.SECRET &&
                                    (secretConfigured ||
                                        field.id in secretsToClear)
                                ) {
                                    TextButton(
                                        enabled = !saving,
                                        onClick = {
                                            values = values + (field.id to "")
                                            secretsToClear += field.id
                                        }
                                    ) {
                                        Text(
                                            AppLanguage.tr(
                                                "清除已保存的值",
                                                "Clear saved value"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val message = validationError.ifBlank { error }
                if (message.isNotBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val invalid = formStore.validate(
                        target.view,
                        values,
                        target.configuredSecrets,
                        secretsToClear
                    )
                    if (invalid == null) {
                        validationError = ""
                        onSave(
                            PluginSettingDraft(
                                values = values,
                                secretsToClear = secretsToClear
                            )
                        )
                    } else {
                        validationError = invalid
                    }
                }
            ) {
                Text(
                    if (saving) {
                        AppLanguage.tr("正在保存…", "Saving…")
                    } else {
                        AppLanguage.tr("保存", "Save")
                    }
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        values = target.view.fields.associate {
                            it.id to it.defaultValue
                        }
                        secretsToClear = target.configuredSecrets
                        validationError = ""
                    }
                ) {
                    Text(AppLanguage.tr("恢复默认", "Reset"))
                }
                TextButton(
                    enabled = !saving,
                    onClick = onDismiss
                ) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        }
    )
}

@Composable
private fun PluginDetailDialog(
    detail: PluginDetail,
    onBuiltinEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(detail.iconFile?.path) {
        detail.iconFile?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path) }
            ?.asImageBitmap()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .heightIn(max = 780.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = detail.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Extension,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${detail.publisher} · v${detail.version}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    detail.description.ifBlank {
                        AppLanguage.tr("暂无插件介绍", "No plugin description")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    detail.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                PluginCapabilitySummary(detail.capabilities)
                if (detail.builtin != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                AppLanguage.tr("启用插件", "Enable plugin"),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                if (detail.builtin.enabled) {
                                    AppLanguage.tr("当前已启用", "Currently enabled")
                                } else {
                                    AppLanguage.tr("当前已停用", "Currently disabled")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = detail.builtin.enabled,
                            onCheckedChange = onBuiltinEnabledChange
                        )
                    }
                }
                val htmlFile = detail.htmlFile
                if (htmlFile != null && htmlFile.isFile) {
                    Text(
                        AppLanguage.tr("插件页面", "Plugin page"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val webView = remember(htmlFile.path) {
                        WebView(context).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.blockNetworkLoads = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?
                                ): Boolean {
                                    val uri = url?.let {
                                        runCatching { android.net.Uri.parse(it) }
                                            .getOrNull()
                                    } ?: return true
                                    if (uri.scheme == "file") return false
                                    context.startActivity(
                                        Intent(
                                            context,
                                            BrowserActivity::class.java
                                        )
                                            .putExtra(
                                                BrowserActivity.EXTRA_URL,
                                                uri.toString()
                                            )
                                            .addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK
                                            )
                                    )
                                    return true
                                }
                            }
                            loadUrl(htmlFile.toURI().toString())
                        }
                    }
                    androidx.compose.runtime.DisposableEffect(webView) {
                        onDispose {
                            webView.stopLoading()
                            webView.loadUrl("about:blank")
                            webView.destroy()
                        }
                    }
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(AppLanguage.tr("关闭", "Close"))
                    }
                }
            }
        }
    }
}

private fun PluginManifest.toDetail(
    context: android.content.Context
): PluginDetail = PluginDetail(
    id = id,
    title = PluginCenterStore.displayName(context, id, name),
    description = description,
    version = version,
    publisher = publisher.ifBlank { "NestSpace" },
    capabilities = capabilities,
    iconFile = PluginSourceRegistry.iconFile(id)
        ?: PluginPackageInstaller.get(context).iconFile(id),
    htmlFile = PluginSourceRegistry.htmlPage(id)
        ?: PluginPackageInstaller.get(context).htmlPage(id)
)

private fun PluginDescriptor.toDetail(
    description: String,
    publisher: String,
    title: String = name,
    builtin: BuiltInPluginEntry? = null
): PluginDetail = PluginDetail(
    id = id,
    title = title,
    description = description,
    version = version,
    publisher = publisher,
    capabilities = capabilities,
    builtin = builtin
)

private fun permissionLabel(permission: PluginPermission): String =
    when (permission) {
        PluginPermission.AGENT -> AppLanguage.tr("Agent", "Agent")
        PluginPermission.RUNTIME -> AppLanguage.tr("运行时", "Runtime")
        PluginPermission.AUTOMATION -> AppLanguage.tr("自动化", "Automation")
        PluginPermission.STORAGE -> AppLanguage.tr("存储", "Storage")
        PluginPermission.SECRETS -> AppLanguage.tr("密钥", "Secrets")
        PluginPermission.CONTENT -> AppLanguage.tr("内容交互", "Content")
        PluginPermission.UI -> AppLanguage.tr("界面", "UI")
        PluginPermission.NOTIFICATIONS -> AppLanguage.tr("通知", "Notifications")
        PluginPermission.NETWORK -> AppLanguage.tr("网络", "Network")
        PluginPermission.DEVICE -> AppLanguage.tr("设备能力", "Device")
    }

private fun categoryLabel(category: PluginCategory): String = when (category) {
    PluginCategory.ALL -> AppLanguage.tr("全部", "All")
    PluginCategory.AGENT -> AppLanguage.tr("Agent", "Agent")
    PluginCategory.DISPLAY -> AppLanguage.tr("显示", "Display")
    PluginCategory.NETWORK -> AppLanguage.tr("网络", "Network")
    PluginCategory.DATA -> AppLanguage.tr("数据", "Data")
    PluginCategory.RUNTIME -> AppLanguage.tr("运行时", "Runtime")
    PluginCategory.SYSTEM -> AppLanguage.tr("系统", "System")
}

private fun PluginManifest.matches(
    query: String,
    category: PluginCategory,
    displayName: String = name
): Boolean {
    val haystack = buildString {
        append(displayName).append(' ')
        append(name).append(' ')
        append(id).append(' ')
        append(publisher).append(' ')
        append(description).append(' ')
        capabilities.forEach { append(it.name).append(' ') }
        PluginPermission.groupsFor(capabilities)
            .forEach { append(it.id()).append(' ') }
    }.lowercase()
    return (query.isBlank() || haystack.contains(query.trim().lowercase())) &&
        capabilities.matchesCategory(category)
}

private fun PluginDescriptor.matches(
    query: String,
    category: PluginCategory,
    description: String = "",
    displayName: String = name
): Boolean {
    val haystack = buildString {
        append(displayName).append(' ')
        append(name).append(' ')
        append(id).append(' ')
        append(description).append(' ')
        capabilities.forEach { append(it.name).append(' ') }
        PluginPermission.groupsFor(capabilities)
            .forEach { append(it.id()).append(' ') }
    }.lowercase()
    return (query.isBlank() || haystack.contains(query.trim().lowercase())) &&
        capabilities.matchesCategory(category)
}

private fun PluginManifest.exportFileName(): String =
    "$id-$version.nestplugin"
        .replace(Regex("[^A-Za-z0-9._-]"), "_")

private fun Set<PluginCapability>.matchesCategory(
    category: PluginCategory
): Boolean = when (category) {
    PluginCategory.ALL -> true
    PluginCategory.AGENT -> any {
        it.name.startsWith("AGENT_") ||
            it == PluginCapability.LLM_PROVIDERS ||
            it == PluginCapability.SPEECH_PROVIDERS ||
            it == PluginCapability.COMMANDS
    }
    PluginCategory.DISPLAY -> any {
        it.name.startsWith("UI_") ||
            it == PluginCapability.AGENT_TOOL_PRESENTATION
    }
    PluginCategory.NETWORK ->
        PluginCapability.NETWORK in this ||
            PluginCapability.WEBSOCKET in this
    PluginCategory.DATA -> any {
        it in setOf(
            PluginCapability.STORAGE,
            PluginCapability.CACHE,
            PluginCapability.DATABASE,
            PluginCapability.SETTINGS,
            PluginCapability.SECRETS,
            PluginCapability.FILE_PICKER,
            PluginCapability.FILE_EXPORT
        )
    }
    PluginCategory.RUNTIME -> any {
        it == PluginCapability.RUNTIME_LAUNCH_POLICY
    }
    PluginCategory.SYSTEM -> any {
        it == PluginCapability.ANDROID_APIS ||
            it == PluginCapability.NOTIFICATIONS ||
            it == PluginCapability.SHARE ||
            it == PluginCapability.CLIPBOARD
    }
}

private fun registeredPlugins(): List<PluginDescriptor> {
    val result = LinkedHashMap<String, PluginDescriptor>()
    RuntimeProxyPlugins.snapshot().forEach { result[it.id] = it }
    AgentPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    PluginSpeechProviders.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    AgentModelPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    AgentToolPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    AgentToolHookPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    AgentToolPresentationPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    PluginCommandPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    PluginUiPlugins.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    PluginLlmProviders.snapshot().forEach {
        result[it.descriptor.id] = it.descriptor
    }
    return result.values
        .filterNot { PluginSourceRegistry.contains(it.id) }
        .sortedBy { it.name.lowercase() }
}
