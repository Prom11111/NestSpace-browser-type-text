package com.coke.nestspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.ReasoningEffort
import java.util.Locale

/**
 * Full-screen editor for one LLM config. Opened as its own settings page so
 * long forms (base URL, key) are not cramped into a dialog. The system prompt
 * is global and lives on its own settings page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LlmConfigEditor(
    config: LlmConfig,
    testing: Boolean,
    status: String,
    onSave: (LlmConfig) -> Unit,
    onTest: (LlmConfig) -> Unit
) {
    var name by remember(config) { mutableStateOf(config.name) }
    var protocol by remember(config) { mutableStateOf(config.protocol) }
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var model by remember(config) { mutableStateOf(config.model) }
    var contextTokens by remember(config) { mutableStateOf(config.contextTokens.toString()) }
    var maxToolRounds by remember(config) { mutableStateOf(config.maxToolRounds.toString()) }
    var maxTokens by remember(config) { mutableStateOf(config.maxTokens.toString()) }
    var temperature by remember(config) { mutableStateOf(config.temperature.toString()) }
    var reasoningEffort by remember(config) { mutableStateOf(config.reasoningEffort) }

    fun contextValue(): Int = (contextTokens.toIntOrNull() ?: config.contextTokens)
        .coerceAtLeast(LlmConfig.MIN_CONTEXT_TOKENS)

    fun stepContext(delta: Int) {
        val next = (contextValue().toLong() + delta)
            .coerceIn(
                LlmConfig.MIN_CONTEXT_TOKENS.toLong(),
                Int.MAX_VALUE.toLong()
            )
        contextTokens = next.toString()
    }

    fun buildConfig(): LlmConfig = config.copy(
        name = name.trim(),
        protocol = protocol,
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        contextTokens = contextValue(),
        maxToolRounds = maxToolRounds.toIntOrNull()
            ?.coerceIn(LlmConfig.MIN_TOOL_ROUNDS, LlmConfig.MAX_TOOL_ROUNDS)
            ?: config.maxToolRounds,
        maxTokens = maxTokens.toIntOrNull()?.coerceIn(16, 32_768) ?: config.maxTokens,
        temperature = temperature.toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 2.0)
            ?: config.temperature,
        reasoningEffort = reasoningEffort
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            AppLanguage.tr(
                "AI 助手只控制 virtual-1 内部应用；模型通过 space_* / ui_* 工具操作。",
                "The AI assistant only controls apps inside virtual-1. The model " +
                    "operates them through the space_* / ui_* tools."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (testing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(AppLanguage.tr("名称", "Name")) },
            placeholder = { Text(AppLanguage.tr("例如 Claude", "e.g. Claude")) },
            singleLine = true
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LlmProtocol.entries.forEach { item ->
                FilterChip(
                    selected = protocol == item,
                    onClick = {
                        if (protocol != item) {
                            val previousDefaults = LlmConfig.defaultsFor(protocol)
                            protocol = item
                            val defaults = LlmConfig.defaultsFor(item)
                            if (name.isBlank() || name == previousDefaults.name) {
                                name = defaults.name
                            }
                            baseUrl = defaults.baseUrl
                            model = defaults.model
                        }
                    },
                    label = { Text(item.label) }
                )
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Base URL") },
            placeholder = {
                Text(
                    when (protocol) {
                        LlmProtocol.ANTHROPIC -> LlmConfig.DEFAULT_ANTHROPIC_BASE
                        LlmProtocol.OPENAI -> LlmConfig.DEFAULT_OPENAI_BASE
                    }
                )
            },
            singleLine = true
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Text(
            AppLanguage.tr(
                "API Key 使用 Android Keystore 加密保存在本机",
                "The API key is encrypted with the Android Keystore on this device"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(AppLanguage.tr("模型", "Model")) },
            placeholder = { Text(AppLanguage.tr("例如 claude-sonnet-5-5 / gpt-4o-mini", "e.g. claude-sonnet-5-5 / gpt-4o-mini")) },
            singleLine = true
        )

        Text(
            AppLanguage.tr("上下文大小", "Context size"),
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { stepContext(-LlmConfig.CONTEXT_TOKEN_STEP) },
                enabled = contextValue() > LlmConfig.MIN_CONTEXT_TOKENS
            ) {
                Text(AppLanguage.tr("−10万", "−100k"))
            }
            OutlinedTextField(
                value = contextTokens,
                onValueChange = { contextTokens = it.filter(Char::isDigit).take(9) },
                modifier = Modifier.weight(1f),
                label = { Text("Tokens") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedButton(
                onClick = { stepContext(LlmConfig.CONTEXT_TOKEN_STEP) }
            ) {
                Text(AppLanguage.tr("+10万", "+100k"))
            }
        }
        Text(
            AppLanguage.tr(
                "默认 800,000；左右按钮每次调整 100,000；最低 100,000，最高不限。" +
                    "当前 ${formatTokens(contextValue())}。",
                "Default 800,000; the left/right buttons step 100,000; minimum " +
                    "100,000, no upper limit. Current: ${formatTokens(contextValue())}."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = maxTokens,
                onValueChange = { maxTokens = it.filter(Char::isDigit) },
                modifier = Modifier.weight(1f),
                label = { Text(AppLanguage.tr("最大输出（不含思考）", "Max answer tokens")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                value = temperature,
                onValueChange = { input ->
                    temperature = input.filter { it.isDigit() || it == '.' }
                },
                modifier = Modifier.weight(1f),
                label = { Text("Temperature") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )
        }
        Text(
            AppLanguage.tr(
                "最大输出只限制可见回答，思考另算。当前思考档位会自动额外预留 " +
                    "${formatTokens(reasoningEffort.reasoningReserveTokens)} tokens；" +
                    "仅对把思考计入 max_tokens 的模型生效。",
                "Max answer tokens limits visible text only; thinking is budgeted " +
                    "separately. The current effort adds " +
                    "${formatTokens(reasoningEffort.reasoningReserveTokens)} tokens of " +
                    "headroom for models that count reasoning in max_tokens."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = maxToolRounds,
            onValueChange = { maxToolRounds = it.filter(Char::isDigit).take(4) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(AppLanguage.tr("最大工具轮次", "Max tool rounds")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        Text(
            AppLanguage.tr(
                "默认 128 轮。达到上限后会停止调用工具，并先让模型输出文字总结。",
                "Default 128 rounds. At the limit the agent stops calling tools and " +
                    "asks the model for a text summary."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(AppLanguage.tr("思考程度", "Reasoning effort"), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ReasoningEffort.entries.forEach { effort ->
                FilterChip(
                    selected = reasoningEffort == effort,
                    onClick = { reasoningEffort = effort },
                    label = { Text(effort.label) }
                )
            }
        }
        Text(
            reasoningEffort.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            when (protocol) {
                LlmProtocol.ANTHROPIC -> AppLanguage.tr(
                    "请求：POST /v1/messages，使用原生 tools / tool_use / tool_result。",
                    "Request: POST /v1/messages with native tools / tool_use / tool_result."
                )
                LlmProtocol.OPENAI -> AppLanguage.tr(
                    "请求：POST /v1/chat/completions，使用原生 tool_calls / tool 消息。",
                    "Request: POST /v1/chat/completions with native tool_calls / tool messages."
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            AppLanguage.tr(
                "系统提示词已移到「空间设置 → 系统提示词」，所有模型共用。",
                "The system prompt now lives under Space settings → System prompt " +
                    "and is shared by all models."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onTest(buildConfig()) },
                enabled = !testing
            ) {
                Text(
                    if (testing) AppLanguage.tr("测试中…", "Testing…")
                    else AppLanguage.tr("测试连接", "Test connection")
                )
            }
            Button(
                onClick = { onSave(buildConfig()) },
                enabled = !testing
            ) {
                Text(AppLanguage.tr("保存", "Save"))
            }
        }
    }
}

private fun formatTokens(value: Int): String =
    String.format(Locale.getDefault(), "%,d", value)
