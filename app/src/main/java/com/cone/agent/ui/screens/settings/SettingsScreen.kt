package com.cone.agent.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import com.cone.agent.core.Constants
import com.cone.agent.data.local.entity.VisionModelView
import com.cone.agent.data.repository.MemoryCategory
import com.cone.agent.data.repository.MemoryItem
import com.cone.agent.data.repository.ScreenMode
import com.cone.agent.ui.theme.ConeShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val allModels by viewModel.allModels.collectAsStateWithLifecycle()
    val selected by viewModel.selectedModel.collectAsStateWithLifecycle()
    val chatSelected by viewModel.chatModel.collectAsStateWithLifecycle()
    val maxSteps by viewModel.maxSteps.collectAsStateWithLifecycle()
    val autoConfirm by viewModel.autoConfirmHighRisk.collectAsStateWithLifecycle()
    val planMode by viewModel.planModeEnabled.collectAsStateWithLifecycle()
    val memoryEnabled by viewModel.memoryEnabled.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val screenMode by viewModel.screenMode.collectAsStateWithLifecycle()
    val visionSelected by viewModel.visionModel.collectAsStateWithLifecycle()
    val chatDualModel by viewModel.chatDualModelEnabled.collectAsStateWithLifecycle()
    val chatVisionSelected by viewModel.chatVisionModel.collectAsStateWithLifecycle()
    val searchEngine by viewModel.searchEngine.collectAsStateWithLifecycle()
    val asrBaseUrl by viewModel.asrBaseUrl.collectAsStateWithLifecycle()
    val asrApiKey by viewModel.asrApiKey.collectAsStateWithLifecycle()
    val asrModel by viewModel.asrModel.collectAsStateWithLifecycle()
    val asrLanguage by viewModel.asrLanguage.collectAsStateWithLifecycle()
    val ttsBaseUrl by viewModel.ttsBaseUrl.collectAsStateWithLifecycle()
    val ttsApiKey by viewModel.ttsApiKey.collectAsStateWithLifecycle()
    val ttsModel by viewModel.ttsModel.collectAsStateWithLifecycle()
    val ttsVoice by viewModel.ttsVoice.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.settings_agent_model_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_agent_model_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            ModelSelector(
                models = allModels,
                selectedProviderId = selected?.providerId,
                selectedModelId = selected?.modelId,
                onSelect = { pid, mid -> viewModel.selectModel(pid, mid) },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // 屏幕识别方式，两级：先选本地 OCR / 云端识别，选了云端再分单模型与拼凑双模型。
            // 只有拼凑双模型才需要另配画面理解模型，所以模型选择器藏在最里层。
            Text(stringResource(R.string.settings_screen_source_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_screen_source_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            ModeOption(
                selected = screenMode == ScreenMode.LOCAL_OCR,
                title = stringResource(R.string.settings_screen_source_local),
                description = stringResource(R.string.settings_screen_source_local_desc),
                onClick = { viewModel.setScreenMode(ScreenMode.LOCAL_OCR) },
            )
            ModeOption(
                selected = screenMode.isCloud,
                title = stringResource(R.string.settings_screen_source_cloud),
                description = stringResource(R.string.settings_screen_source_cloud_desc),
                // Landing on the simpler of the two cloud paths; the sub-choice below refines it.
                onClick = { viewModel.setScreenMode(ScreenMode.CLOUD_SINGLE) },
            )

            if (screenMode.isCloud) {
                Column(modifier = Modifier.padding(start = 32.dp, top = 4.dp)) {
                    ModeOption(
                        selected = screenMode == ScreenMode.CLOUD_SINGLE,
                        title = stringResource(R.string.settings_screen_source_cloud_single),
                        description = stringResource(R.string.settings_screen_source_cloud_single_desc),
                        onClick = { viewModel.setScreenMode(ScreenMode.CLOUD_SINGLE) },
                    )
                    ModeOption(
                        selected = screenMode == ScreenMode.CLOUD_DUAL,
                        title = stringResource(R.string.settings_screen_source_cloud_dual),
                        description = stringResource(R.string.settings_screen_source_cloud_dual_desc),
                        onClick = { viewModel.setScreenMode(ScreenMode.CLOUD_DUAL) },
                    )
                    // Only a factual gap is flagged: pairing with no vision model chosen has
                    // nothing to describe the screenshot with. Whether a model can read an image is
                    // the user's call, not something guessed from a capability flag.
                    if (screenMode == ScreenMode.CLOUD_DUAL && visionSelected == null) {
                        SettingWarning(stringResource(R.string.settings_warn_no_vision_model))
                    }

                    if (screenMode == ScreenMode.CLOUD_DUAL) {
                        Text(
                            stringResource(R.string.settings_vision_model_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            stringResource(R.string.settings_vision_model_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        ModelSelector(
                            models = allModels,
                            selectedProviderId = visionSelected?.providerId,
                            selectedModelId = visionSelected?.modelId,
                            onSelect = { pid, mid -> viewModel.selectVisionModel(pid, mid) },
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(stringResource(R.string.settings_chat_model_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_chat_model_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            ModelSelector(
                models = allModels,
                selectedProviderId = chatSelected?.providerId,
                selectedModelId = chatSelected?.modelId,
                onSelect = { pid, mid -> viewModel.selectChatModel(pid, mid) },
            )

            // 问答拼凑（双模型）：独立于智能体——一个模型看图描述、问答模型据描述作答。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_chat_dual_model_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_chat_dual_model_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(
                    checked = chatDualModel,
                    onCheckedChange = { viewModel.setChatDualModelEnabled(it) },
                )
            }

            if (chatDualModel) {
                Text(
                    stringResource(R.string.settings_chat_vision_model_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.settings_chat_vision_model_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                ModelSelector(
                    models = allModels,
                    selectedProviderId = chatVisionSelected?.providerId,
                    selectedModelId = chatVisionSelected?.modelId,
                    onSelect = { pid, mid -> viewModel.selectChatVisionModel(pid, mid) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Built-in browser search engine.
            Text(stringResource(R.string.settings_search_engine_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_search_engine_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            listOf(
                "auto" to stringResource(R.string.search_engine_auto),
                "baidu" to stringResource(R.string.search_engine_baidu),
                "google" to stringResource(R.string.search_engine_google),
                "bing" to stringResource(R.string.search_engine_bing),
            ).forEach { (id, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusProperties { canFocus = false }
                        .clickable { viewModel.setSearchEngine(id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = searchEngine == id, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            val unlimitedSteps = maxSteps >= Constants.UNLIMITED_MAX_STEPS
            Text(
                if (unlimitedSteps) {
                    stringResource(R.string.settings_max_steps_unlimited)
                } else {
                    stringResource(R.string.settings_max_steps, maxSteps)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_unlimited_steps_title), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = unlimitedSteps,
                    onCheckedChange = { on ->
                        viewModel.setMaxSteps(if (on) Constants.UNLIMITED_MAX_STEPS else Constants.DEFAULT_MAX_STEPS)
                    },
                )
            }
            Slider(
                value = (if (unlimitedSteps) Constants.DEFAULT_MAX_STEPS else maxSteps).toFloat(),
                onValueChange = { viewModel.setMaxSteps(it.toInt()) },
                valueRange = 5f..60f,
                enabled = !unlimitedSteps,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.settings_max_steps_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_plan_mode_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_plan_mode_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = planMode,
                    onCheckedChange = { viewModel.setPlanModeEnabled(it) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_auto_confirm_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_auto_confirm_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Switch(
                    checked = autoConfirm,
                    onCheckedChange = { viewModel.setAutoConfirmHighRisk(it) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // 问答记忆（Gemini 式 Saved Info）：开关 + 已记住条目管理（逐条删除 / 清空全部）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_memory_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_memory_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = memoryEnabled,
                    onCheckedChange = { viewModel.setMemoryEnabled(it) },
                )
            }
            if (memories.isEmpty()) {
                Text(
                    stringResource(R.string.settings_memory_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                // Memories accumulate on their own now, so this list is no longer a handful of items
                // the user typed — it can reach MAX_ITEMS and bury everything below it. Collapsed by
                // default, showing the newest few, with the count on the toggle so it stays obvious
                // that more is stored.
                var memoriesExpanded by remember { mutableStateOf(false) }
                var editing by remember { mutableStateOf<MemoryItem?>(null) }
                // Newest first, so what the assistant just learned is what the user sees.
                val ordered = memories.sortedByDescending { it.updatedAt }
                val shown = if (memoriesExpanded) ordered else ordered.take(COLLAPSED_MEMORIES)
                // Grouped only once expanded: the collapsed peek is a short recency list, while the
                // full view is a profile the user reads by dimension.
                if (memoriesExpanded) {
                    shown.groupBy { it.categoryEnum }
                        .toList()
                        .sortedBy { it.first.ordinal }
                        .forEach { (category, items) ->
                            Text(
                                stringResource(memoryCategoryLabel(category)),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            )
                            items.forEach { item ->
                                MemoryRow(
                                    item = item,
                                    onEdit = { editing = item },
                                    onDelete = { viewModel.deleteMemory(item.id) },
                                )
                            }
                        }
                } else {
                    shown.forEach { item ->
                        MemoryRow(
                            item = item,
                            onEdit = { editing = item },
                            onDelete = { viewModel.deleteMemory(item.id) },
                        )
                    }
                }
                editing?.let { item ->
                    MemoryEditDialog(
                        initial = item.content,
                        onDismiss = { editing = null },
                        onConfirm = { text -> viewModel.updateMemory(item.id, text); editing = null },
                    )
                }
                if (memories.size > COLLAPSED_MEMORIES) {
                    TextButton(onClick = { memoriesExpanded = !memoriesExpanded }) {
                        Text(
                            if (memoriesExpanded) {
                                stringResource(R.string.settings_memory_collapse)
                            } else {
                                stringResource(R.string.settings_memory_expand, memories.size)
                            },
                        )
                    }
                }
                var showClearMemories by remember { mutableStateOf(false) }
                TextButton(onClick = { showClearMemories = true }) {
                    Text(stringResource(R.string.settings_memory_clear))
                }
                if (showClearMemories) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showClearMemories = false },
                        title = { Text(stringResource(R.string.settings_memory_clear_title)) },
                        text = { Text(stringResource(R.string.settings_memory_clear_msg)) },
                        confirmButton = {
                            TextButton(onClick = { viewModel.clearMemories(); showClearMemories = false }) {
                                Text(stringResource(R.string.action_delete))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearMemories = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // 自定义语音识别：留空则用系统识别。折叠收纳——大多数人不会填。
            var asrExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = false }
                    .clickable { asrExpanded = !asrExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_asr_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (asrBaseUrl.isBlank()) {
                            stringResource(R.string.settings_asr_using_system)
                        } else {
                            stringResource(R.string.settings_asr_using_custom, asrModel.ifBlank { "whisper-1" })
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Icon(
                    if (asrExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (asrExpanded) {
                Text(
                    stringResource(R.string.settings_asr_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = asrBaseUrl,
                    onValueChange = { viewModel.setAsrBaseUrl(it) },
                    label = { Text(stringResource(R.string.settings_asr_url_label)) },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = asrApiKey,
                    onValueChange = { viewModel.setAsrApiKey(it) },
                    label = { Text(stringResource(R.string.settings_asr_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = asrModel,
                    onValueChange = { viewModel.setAsrModel(it) },
                    label = { Text(stringResource(R.string.settings_asr_model_label)) },
                    placeholder = { Text("whisper-1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = asrLanguage,
                    onValueChange = { viewModel.setAsrLanguage(it) },
                    label = { Text(stringResource(R.string.settings_asr_lang_label)) },
                    placeholder = { Text("zh") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.settings_asr_wake_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // 自定义语音合成：留空则用系统 TTS。与 ASR 一样折叠收纳。
            var ttsExpanded by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusProperties { canFocus = false }
                    .clickable { ttsExpanded = !ttsExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_tts_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (ttsBaseUrl.isBlank()) {
                            stringResource(R.string.settings_tts_using_system)
                        } else {
                            stringResource(R.string.settings_tts_using_custom, ttsModel.ifBlank { "tts-1" })
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Icon(
                    if (ttsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ttsExpanded) {
                Text(
                    stringResource(R.string.settings_tts_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = ttsBaseUrl,
                    onValueChange = { viewModel.setTtsBaseUrl(it) },
                    label = { Text(stringResource(R.string.settings_tts_url_label)) },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ttsApiKey,
                    onValueChange = { viewModel.setTtsApiKey(it) },
                    label = { Text(stringResource(R.string.settings_tts_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = ttsModel,
                    onValueChange = { viewModel.setTtsModel(it) },
                    label = { Text(stringResource(R.string.settings_tts_model_label)) },
                    placeholder = { Text("tts-1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = ttsVoice,
                    onValueChange = { viewModel.setTtsVoice(it) },
                    label = { Text(stringResource(R.string.settings_tts_voice_label)) },
                    placeholder = { Text("alloy") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // 法律协议：用户默认同意，点击可查看完整文本。
            Text(
                stringResource(R.string.settings_legal_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.settings_legal_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            var showTos by remember { mutableStateOf(false) }
            var showPrivacy by remember { mutableStateOf(false) }
            LegalEntry(stringResource(R.string.legal_tos_title)) { showTos = true }
            LegalEntry(stringResource(R.string.legal_privacy_title)) { showPrivacy = true }
            Text(
                stringResource(R.string.legal_default_consent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (showTos) {
                LegalSheet(
                    title = stringResource(R.string.legal_tos_title),
                    body = stringResource(R.string.legal_tos_body),
                    onDismiss = { showTos = false },
                )
            }
            if (showPrivacy) {
                LegalSheet(
                    title = stringResource(R.string.legal_privacy_title),
                    body = stringResource(R.string.legal_privacy_body),
                    onDismiss = { showPrivacy = false },
                )
            }
        }
    }
}

/** 一行法律协议入口，点击展开全文。 */
/** How many memories the settings list shows before the user expands it. */
private const val COLLAPSED_MEMORIES = 5

private fun memoryCategoryLabel(category: MemoryCategory): Int = when (category) {
    MemoryCategory.IDENTITY -> R.string.memory_category_identity
    MemoryCategory.PREFERENCE -> R.string.memory_category_preference
    MemoryCategory.RELATIONSHIP -> R.string.memory_category_relationship
    MemoryCategory.HABIT -> R.string.memory_category_habit
    MemoryCategory.GOAL -> R.string.memory_category_goal
    MemoryCategory.OTHER -> R.string.memory_category_other
}

/** One saved fact: tap the text to correct it, the ✕ to forget it. */
@Composable
private fun MemoryRow(item: MemoryItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .focusProperties { canFocus = false }
                .clickable(onClick = onEdit),
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.cd_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Correcting a memory matters more now that they're written automatically: a fact extracted from a
 * misheard voice message or an offhand remark should be fixable, not only deletable.
 */
@Composable
private fun MemoryEditDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_memory_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text != initial,
            ) { Text(stringResource(R.string.settings_memory_edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** An inline caution under a setting that is selectable but currently can't take effect. */
@Composable
private fun SettingWarning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 40.dp, top = 2.dp, bottom = 4.dp),
    )
}

/** One radio choice with its explanation — used for the nested 屏幕识别方式 tree. */
@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegalEntry(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 以底部弹层展示协议全文，可滚动阅读；用户已默认同意，按钮文案为"已阅"。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalSheet(title: String, body: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                // heightIn 必须在 verticalScroll 外侧：限的是可视窗口高度；反过来会把正文内容
                // 裁成 480dp，协议只剩第一屏且无法滚动。
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.legal_ack))
            }
        }
    }
}

/**
 * Compact model picker: a single field-style row showing the current choice, tapping it drops down a
 * Pixel-rounded, height-capped scrollable list. Replaces the old flat wall of one card per model —
 * with several picker sections on the settings page, listing every model inline got very long.
 */
@Composable
private fun ModelSelector(
    models: List<VisionModelView>,
    selectedProviderId: Long?,
    selectedModelId: String?,
    onSelect: (Long, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = models.firstOrNull {
        it.providerId == selectedProviderId && it.modelId == selectedModelId
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable(enabled = models.isNotEmpty()) { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (current != null) {
                    Text(current.modelId, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        current.providerName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        stringResource(if (models.isEmpty()) R.string.settings_no_models else R.string.select_model),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.clip(ConeShapes.Menu).heightIn(max = 360.dp),
        ) {
            models.forEach { m ->
                val isSel = m.providerId == selectedProviderId && m.modelId == selectedModelId
                DropdownMenuItem(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                    text = {
                        Column {
                            Text(
                                m.modelId,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                m.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    trailingIcon = if (isSel) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = { expanded = false; onSelect(m.providerId, m.modelId) },
                )
            }
        }
    }
}
