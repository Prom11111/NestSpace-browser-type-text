package com.coke.nestspace.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.memory.MemoryItem
import com.coke.nestspace.memory.MemoryKind
import com.coke.nestspace.memory.BuiltinSkills
import com.coke.nestspace.memory.MemoryStore
import com.coke.nestspace.memory.SkillItem
import com.coke.nestspace.memory.SkillStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MemorySkillsScreen() {
    AppLanguage.isChinese()
    val context = LocalContext.current
    val memoryStore = remember { MemoryStore(context) }
    val skillStore = remember { SkillStore(context) }
    var memories by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var skills by remember { mutableStateOf<List<SkillItem>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    var editingSkill by remember { mutableStateOf<SkillItem?>(null) }
    var historySkill by remember { mutableStateOf<SkillItem?>(null) }
    val settingsRevision by AppSettingsEvents.revision.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh) {
        memories = withContext(Dispatchers.IO) { memoryStore.list() }
        skills = withContext(Dispatchers.IO) { skillStore.list() }
    }
    LaunchedEffect(settingsRevision) {
        if (settingsRevision > 0) refresh++
    }

    fun reload() {
        refresh++
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "skill.md"
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: return@runCatching null
                    val parsed = BuiltinSkills.parse(
                        fileName.substringBeforeLast('.', "skill"),
                        text
                    ) ?: return@runCatching null
                    skillStore.save(
                        name = parsed.name,
                        description = parsed.description,
                        body = parsed.body,
                        source = "import: $fileName",
                        enabled = false
                    ).getOrNull()
                }.getOrNull()
            }
            if (saved == null) {
                Toast.makeText(
                    context,
                    AppLanguage.tr("无法导入技能文件", "Cannot import this skill file"),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                AppSettingsEvents.notifyChanged()
                reload()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSection(AppLanguage.tr("记忆", "Memory"))
        }
        item {
            Text(
                AppLanguage.tr(
                    "助手会自动整理对话里的习惯、关键事实和复杂做法。标准权限下直接生效；严格权限下做法会先出现在对话里等你确认。也可以在这里管理。",
                    "The assistant automatically saves habits, key facts and procedures from the " +
                        "conversation. They take effect immediately in Standard mode; Strict mode " +
                        "shows confirmation cards in the chat. They can also be managed here."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (memories.isEmpty()) {
            item { EmptyCard(AppLanguage.tr("还没有记忆", "No memories yet")) }
        } else {
            memories.forEach { item ->
                item(key = "memory-${item.id}") {
                    MemoryCard(
                        item = item,
                        onApprove = {
                            memoryStore.approve(item.id)
                            reload()
                        },
                        onPin = {
                            memoryStore.setPinned(item.id, !item.pinned)
                            reload()
                        },
                        onDelete = {
                            pendingDelete = DeleteTarget.Memory(item)
                        }
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            SettingsSection(AppLanguage.tr("技能", "Skills"))
        }
        item {
            Text(
                AppLanguage.tr(
                    "助手会从成功的多步骤任务自动起草技能；启用后会进入技能索引。连续失败会自动停用，内置技能可停用但不可删除。",
                    "The assistant drafts skills from successful multi-step tasks; enabled skills enter the skill " +
                        "index. Repeated failures auto-disable a skill. Built-in skills can be disabled but not deleted."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            OutlinedButton(
                onClick = {
                    importLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                }
            ) {
                Text(AppLanguage.tr("导入 SKILL.md", "Import SKILL.md"))
            }
        }
        if (skills.isEmpty()) {
            item { EmptyCard(AppLanguage.tr("还没有技能", "No skills yet")) }
        } else {
            skills.forEach { item ->
                item(key = "skill-${item.id}") {
                    SkillCard(
                        item = item,
                        onEnabled = {
                            skillStore.setEnabled(item.id, it)
                            reload()
                        },
                        onEdit = { editingSkill = item },
                        onHistory = { historySkill = item },
                        onDelete = {
                            pendingDelete = DeleteTarget.Skill(item)
                        }
                    )
                }
            }
        }
        item {
            Text(
                AppLanguage.tr(
                    "助手不会自动记住密码、验证码、银行卡或身份证信息。",
                    "The assistant never stores passwords, verification codes, cards, or ID numbers."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    when (target) {
                        is DeleteTarget.Memory -> AppLanguage.tr("删除这条记忆？", "Delete this memory?")
                        is DeleteTarget.Skill -> AppLanguage.tr("删除这个技能？", "Delete this skill?")
                    }
                )
            },
            text = {
                Text(
                    when (target) {
                        is DeleteTarget.Memory -> target.item.content
                        is DeleteTarget.Skill -> target.item.name
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (target) {
                            is DeleteTarget.Memory -> memoryStore.forget(target.item.id)
                            is DeleteTarget.Skill -> skillStore.delete(target.item.id)
                        }
                        pendingDelete = null
                        reload()
                    }
                ) {
                    Text(AppLanguage.tr("删除", "Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    editingSkill?.let { item ->
        var editDescription by remember(item.id) { mutableStateOf(item.description) }
        var editBody by remember(item.id) { mutableStateOf(item.body) }
        AlertDialog(
            onDismissRequest = { editingSkill = null },
            title = { Text(AppLanguage.tr("编辑技能：${item.name}", "Edit skill: ${item.name}")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it },
                        label = { Text(AppLanguage.tr("描述", "Description")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editBody,
                        onValueChange = { editBody = it },
                        label = { Text(AppLanguage.tr("内容", "Body")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 340.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editBody.isNotBlank(),
                    onClick = {
                        val result = skillStore.save(
                            name = item.name,
                            description = editDescription,
                            body = editBody,
                            source = item.source,
                            enabled = item.enabled
                        )
                        if (result.isSuccess) {
                            editingSkill = null
                            AppSettingsEvents.notifyChanged()
                            reload()
                        } else {
                            Toast.makeText(
                                context,
                                result.exceptionOrNull()?.message ?: "save failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text(AppLanguage.tr("保存", "Save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSkill = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    historySkill?.let { item ->
        AlertDialog(
            onDismissRequest = { historySkill = null },
            title = { Text(AppLanguage.tr("版本历史", "Version history")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(item.revisions.asReversed()) { revision ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                revisionTime(revision.at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (revision.description.isNotBlank()) {
                                Text(
                                    revision.description,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                revision.body.take(220),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(
                                onClick = {
                                    val result = skillStore.restoreRevision(item.id, revision.at)
                                    if (result?.isSuccess == true) {
                                        historySkill = null
                                        AppSettingsEvents.notifyChanged()
                                        reload()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            AppLanguage.tr("恢复失败", "Restore failed"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Text(AppLanguage.tr("恢复此版本", "Restore"))
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { historySkill = null }) {
                    Text(AppLanguage.tr("关闭", "Close"))
                }
            }
        )
    }
}

private fun revisionTime(at: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at))

@Composable
private fun MemoryCard(
    item: MemoryItem,
    onApprove: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    memoryKindLabel(item.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = if (item.approved) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        Color(0xFFFF9F0A).copy(alpha = 0.12f)
                    }
                ) {
                    Text(
                        if (item.approved) {
                            AppLanguage.tr("已批准", "Approved")
                        } else {
                            AppLanguage.tr("待确认", "Pending")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.approved) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color(0xFFFF9F0A)
                        },
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.pinned) {
                    Text(
                        AppLanguage.tr("置顶", "Pinned"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(item.content, style = MaterialTheme.typography.bodyMedium)
            if (item.source.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    AppLanguage.tr("来源：${item.source}", "Source: ${item.source}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (!item.approved) {
                    TextButton(onClick = onApprove) {
                        Text(AppLanguage.tr("批准", "Approve"))
                    }
                }
                TextButton(onClick = onPin) {
                    Text(
                        if (item.pinned) {
                            AppLanguage.tr("取消置顶", "Unpin")
                        } else {
                            AppLanguage.tr("置顶", "Pin")
                        }
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(AppLanguage.tr("删除", "Delete"))
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    item: SkillItem,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (item.builtIn) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            ) {
                                Text(
                                    AppLanguage.tr("内置", "Built-in"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    if (item.description.isNotBlank()) {
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!item.builtIn && item.useCount > 0) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            AppLanguage.tr(
                                "使用 ${item.useCount} 次 · 成功 ${item.successCount} · 失败 ${item.failureCount}",
                                "Used ${item.useCount} · ${item.successCount} succeeded · ${item.failureCount} failed"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (item.autoSuspended) {
                        Text(
                            AppLanguage.tr(
                                "最近失败较多，已自动停用",
                                "Auto-disabled after repeated failures"
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Switch(checked = item.enabled, onCheckedChange = onEnabled)
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    item.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) {
                            AppLanguage.tr("收起", "Collapse")
                        } else {
                            AppLanguage.tr("查看内容", "View")
                        }
                    )
                }
                if (item.revisions.isNotEmpty()) {
                    TextButton(onClick = onHistory) {
                        Text(AppLanguage.tr("历史", "History"))
                    }
                }
                if (!item.builtIn) {
                    TextButton(onClick = onEdit) {
                        Text(AppLanguage.tr("编辑", "Edit"))
                    }
                    TextButton(onClick = onDelete) {
                        Text(AppLanguage.tr("删除", "Delete"))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private sealed class DeleteTarget {
    data class Memory(val item: MemoryItem) : DeleteTarget()
    data class Skill(val item: SkillItem) : DeleteTarget()
}

private fun memoryKindLabel(kind: MemoryKind): String = when (kind) {
    MemoryKind.FACT -> AppLanguage.tr("事实", "Fact")
    MemoryKind.PREFERENCE -> AppLanguage.tr("偏好", "Preference")
    MemoryKind.PROCEDURE -> AppLanguage.tr("做法", "Procedure")
}
