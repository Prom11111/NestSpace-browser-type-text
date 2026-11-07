package com.coke.nestspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.ReasoningEffort

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
    var showApiKey by remember(config) { mutableStateOf(false) }
    var model by remember(config) { mutableStateOf(config.model) }
    var contextTokens by remember(config) { mutableStateOf(config.contextTokens.toString()) }
    var maxToolRounds by remember(config) { mutableStateOf(config.maxToolRounds.toString()) }
    var maxTokens by remember(config) { mutableStateOf(config.maxTokens.toString()) }
    var temperature by remember(config) { mutableStateOf(config.temperature.toString()) }
    var reasoningEffort by remember(config) { mutableStateOf(config.reasoningEffort) }
    var showPresets by remember(config) { mutableStateOf(false) }

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
    ).withNormalizedEndpoint()

    fun normalizedConfig(): LlmConfig {
        val normalized = buildConfig()
        if (normalized.baseUrl != baseUrl) baseUrl = normalized.baseUrl
        return normalized
    }

    fun applyPreset(preset: ProviderPreset) {
        val previousDefaultName = LlmConfig.defaultsFor(protocol).name
        if (name.isBlank() || name == previousDefaultName ||
            providerPresets(protocol).any { it.name == name }
        ) {
            name = preset.name
        }
        baseUrl = preset.baseUrl
        model = preset.model
        showPresets = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.weight(1f),
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
            OutlinedButton(
                onClick = { showPresets = true },
                modifier = Modifier.height(56.dp)
            ) {
                Text(AppLanguage.tr("预设", "Presets"))
            }
        }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API Key") },
            visualTransformation = if (showApiKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showApiKey = !showApiKey }) {
                    Icon(
                        imageVector = if (showApiKey) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (showApiKey) {
                            AppLanguage.tr("隐藏 API Key", "Hide API key")
                        } else {
                            AppLanguage.tr("显示 API Key", "Show API key")
                        }
                    )
                }
            },
            singleLine = true
        )

        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(AppLanguage.tr("模型", "Model")) },
            placeholder = { Text(AppLanguage.tr("例如 claude-sonnet-5-5 / gpt-6.1-sol", "e.g. claude-sonnet-5-5 / gpt-6.1-sol")) },
            singleLine = true
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
                Text("-")
            }
            OutlinedTextField(
                value = contextTokens,
                onValueChange = { contextTokens = it.filter(Char::isDigit).take(9) },
                modifier = Modifier.weight(1f),
                label = {
                    Text(AppLanguage.tr("最大上下文 tokens", "Max context tokens"))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedButton(
                onClick = { stepContext(LlmConfig.CONTEXT_TOKEN_STEP) }
            ) {
                Text("+")
            }
        }

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

        OutlinedTextField(
            value = maxToolRounds,
            onValueChange = { maxToolRounds = it.filter(Char::isDigit).take(4) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(AppLanguage.tr("最大工具轮次", "Max tool rounds")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onTest(normalizedConfig()) },
                enabled = !testing
            ) {
                Text(
                    if (testing) AppLanguage.tr("测试中…", "Testing…")
                    else AppLanguage.tr("测试连接", "Test connection")
                )
            }
            Button(
                onClick = { onSave(normalizedConfig()) },
                enabled = !testing
            ) {
                Text(AppLanguage.tr("保存", "Save"))
            }
        }
    }

    if (showPresets) {
        val presets = providerPresets(protocol)
        AlertDialog(
            onDismissRequest = { showPresets = false },
            title = {
                Text(
                    when (protocol) {
                        LlmProtocol.ANTHROPIC ->
                            AppLanguage.tr("Claude 兼容预设", "Claude-compatible presets")
                        LlmProtocol.OPENAI ->
                            AppLanguage.tr("OpenAI 兼容预设", "OpenAI-compatible presets")
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            AssistChip(
                                onClick = { applyPreset(preset) },
                                label = { Text(preset.name) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresets = false }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

private data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val model: String
)

private fun providerPresets(protocol: LlmProtocol): List<ProviderPreset> = when (protocol) {
    LlmProtocol.ANTHROPIC -> listOf(
        ProviderPreset(
            name = "Anthropic",
            baseUrl = LlmConfig.DEFAULT_ANTHROPIC_BASE,
            model = "claude-haiku-5-5"
        ),
        ProviderPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/anthropic/v1/messages",
            model = "deepseek-flash"
        ),
        ProviderPreset(
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/anthropic/v1/messages",
            model = "kimi-k3.1-pro"
        ),
        ProviderPreset(
            name = AppLanguage.tr("智谱 GLM", "Zhipu GLM"),
            baseUrl = "https://open.bigmodel.cn/api/anthropic/v1/messages",
            model = "glm-5.3"
        ),
        ProviderPreset(
            name = AppLanguage.tr("阿里云百炼", "Alibaba Cloud"),
            baseUrl = "https://dashscope.aliyuncs.com/apps/anthropic/v1/messages",
            model = "qwen3.8-flash"
        ),
        ProviderPreset(
            name = AppLanguage.tr("火山方舟", "Volcengine Ark"),
            baseUrl = "https://ark.cn-beijing.volces.com/api/compatible/v1/messages",
            model = "doubao-seed-2-1-pro-260915"
        ),
        ProviderPreset(
            name = AppLanguage.tr("百度千帆", "Baidu Qianfan"),
            baseUrl = "https://qianfan.baidubce.com/anthropic/tokenplan/personal/v1/messages",
            model = "deepseek-v4-flash"
        ),
        ProviderPreset(
            name = AppLanguage.tr("腾讯混元", "Tencent Hunyuan"),
            baseUrl = "https://api.lkeap.cloud.tencent.com/plan/anthropic/v1/messages",
            model = "tc-code-latest"
        ),
        ProviderPreset(
            name = "MiniMax",
            baseUrl = "https://api.minimax.cn/anthropic/v1/messages",
            model = "MiniMax-M3.1-Flash"
        ),
        ProviderPreset(
            name = AppLanguage.tr("阶跃星辰", "StepFun"),
            baseUrl = "https://api.stepfun.com/step_plan/v1/messages",
            model = "step-5-preview"
        ),
        ProviderPreset(
            name = AppLanguage.tr("讯飞星火", "iFlytek Spark"),
            baseUrl = "https://maas-api.cn-huabei-1.xf-yun.com/anthropic/v1/messages",
            model = "spark-x2.6"
        ),
        ProviderPreset(
            name = AppLanguage.tr("小米 MiMo", "Xiaomi MiMo"),
            baseUrl = "https://api.xiaomimimo.com/anthropic/v1/messages",
            model = "mimo-v2.6-pro"
        ),
        ProviderPreset(
            name = AppLanguage.tr("美团 LongCat", "Meituan LongCat"),
            baseUrl = "https://api.longcat.chat/anthropic/v1/messages",
            model = "LongCat-2.0"
        )
    )
    LlmProtocol.OPENAI -> listOf(
        ProviderPreset(
            name = "OpenAI",
            baseUrl = LlmConfig.DEFAULT_OPENAI_BASE,
            model = LlmConfig.DEFAULT_OPENAI_MODEL
        ),
        ProviderPreset(
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1/chat/completions",
            model = "deepseek-flash"
        ),
        ProviderPreset(
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1/chat/completions",
            model = "kimi-k3"
        ),
        ProviderPreset(
            name = AppLanguage.tr("智谱 GLM", "Zhipu GLM"),
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            model = "glm-5.3-flash"
        ),
        ProviderPreset(
            name = AppLanguage.tr("阿里云百炼", "Alibaba Cloud"),
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            model = "qwen3.8-flash"
        ),
        ProviderPreset(
            name = AppLanguage.tr("火山方舟", "Volcengine Ark"),
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            model = "doubao-seed-2-1-pro-260915"
        ),
        ProviderPreset(
            name = AppLanguage.tr("百度千帆", "Baidu Qianfan"),
            baseUrl = "https://qianfan.baidubce.com/v2/chat/completions",
            model = "deepseek-v4-flash"
        ),
        ProviderPreset(
            name = AppLanguage.tr("腾讯混元", "Tencent Hunyuan"),
            baseUrl = "https://tokenhub.tencentmaas.com/v1/chat/completions",
            model = "hy3"
        ),
        ProviderPreset(
            name = "MiniMax",
            baseUrl = "https://api.minimax.cn/v1/chat/completions",
            model = "MiniMax-M3.1-Flash"
        ),
        ProviderPreset(
            name = AppLanguage.tr("阶跃星辰", "StepFun"),
            baseUrl = "https://api.stepfun.com/v1/chat/completions",
            model = "step-3.7-flash"
        ),
        ProviderPreset(
            name = "xAI",
            baseUrl = "https://api.x.ai/v1/chat/completions",
            model = "grok-4.7"
        ),
        ProviderPreset(
            name = "Mistral AI",
            baseUrl = "https://api.mistral.ai/v1/chat/completions",
            model = "mistral-large-4"
        ),
        ProviderPreset(
            name = AppLanguage.tr("小米 MiMo", "Xiaomi MiMo"),
            baseUrl = "https://api.xiaomimimo.com/v1/chat/completions",
            model = "mimo-v2.6-flash"
        ),
        ProviderPreset(
            name = AppLanguage.tr("美团 LongCat", "Meituan LongCat"),
            baseUrl = "https://api.longcat.chat/openai/v1/chat/completions",
            model = "LongCat-2.0"
        )
    )
}
