package com.cone.agent.watch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import com.cone.agent.remote.PortraitCaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * "配置手表端" — the phone half of pairing.
 *
 * Material 3 throughout, unlike the remote-control screen next to it in the drawer: that one
 * deliberately mimics ConeCode's desktop palette because it mirrors a desktop session, whereas this
 * is an ordinary phone settings flow and should look like the rest of the app.
 *
 * The scanner is the very same [ScanContract] + [PortraitCaptureActivity] pair the ConeCode pairing
 * screen uses, so there is one camera surface in the app rather than two that drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchSetupScreen(
    onBack: () -> Unit,
    viewModel: WatchSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scanPrompt = stringResource(R.string.watch_scan_prompt)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        viewModel.onScanned(result.contents)
    }
    val launchScan = {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setOrientationLocked(true)
                .setBeepEnabled(false)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
                .setPrompt(scanPrompt)
                // Same scanner as the ConeCode pairing screen, relabelled — otherwise it greets the
                // user with "扫描 ConeCode 二维码" while they are pointing it at a watch.
                .addExtra(PortraitCaptureActivity.EXTRA_TITLE, R.string.watch_scan_title)
                .addExtra(PortraitCaptureActivity.EXTRA_HINT, R.string.watch_scan_prompt),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.watch_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when (state.step) {
                WatchSetupStep.SCAN -> ScanStep(
                    state = state,
                    onScan = launchScan,
                    onEditSpeech = viewModel::updateSpeech,
                )

                WatchSetupStep.CONNECTING -> ConnectingStep(state = state)

                WatchSetupStep.PUSHING -> PushingStep(state = state)

                WatchSetupStep.DONE -> DoneStep(state = state, onDone = onBack, onAgain = viewModel::restart)

                WatchSetupStep.FAILED -> FailedStep(
                    state = state,
                    onRetry = viewModel::retry,
                    onRescan = { viewModel.restart(); launchScan() },
                    onManual = viewModel::connectManually,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------- steps

@Composable
private fun ScanStep(
    state: WatchSetupUiState,
    onScan: () -> Unit,
    onEditSpeech: ((WatchSpeechPrefs) -> WatchSpeechPrefs) -> Unit,
) {
    val providerCount = state.providerCount
    HeroHeader(
        icon = Icons.Outlined.Watch,
        title = stringResource(R.string.watch_hero_title),
        subtitle = stringResource(R.string.watch_hero_desc),
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            StepLine(1, stringResource(R.string.watch_step_open_watch))
            StepLine(2, stringResource(R.string.watch_step_scan))
            StepLine(3, stringResource(R.string.watch_step_hotspot))
            StepLine(4, stringResource(R.string.watch_step_auto))
        }
    }

    Button(
        onClick = onScan,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
    ) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.watch_scan_button))
    }

    // Pushing an empty provider list would "succeed" and leave the watch just as unusable, so say so
    // before the camera opens rather than after.
    if (providerCount == 0) {
        NoticeRow(
            icon = Icons.Outlined.ErrorOutline,
            text = stringResource(R.string.watch_no_providers),
            tint = MaterialTheme.colorScheme.error,
        )
    } else {
        NoticeRow(
            icon = Icons.Outlined.SettingsEthernet,
            text = stringResource(R.string.watch_will_send, providerCount),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SpeechSection(
        prefs = state.speech,
        providers = state.providerNames,
        onEdit = onEditSpeech,
    )
}

// ---------------------------------------------------------------------------- watch speech form

/**
 * The watch's speech settings, filled in here.
 *
 * They belong to the watch, but a transcription model id is not something anyone types on a 40mm
 * screen — and since the offline model was dropped from the watch build, a provider-backed
 * recognizer is the only way it hears anything on a device whose ROM ships none. Collapsed by
 * default: the pairing itself works without touching this.
 */
@Composable
private fun SpeechSection(
    prefs: WatchSpeechPrefs,
    providers: List<String>,
    onEdit: ((WatchSpeechPrefs) -> WatchSpeechPrefs) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val configured = prefs.ttsUseProvider || prefs.asrUseProvider

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.watch_speech_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (configured) R.string.watch_speech_summary_on else R.string.watch_speech_summary_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(
                    stringResource(R.string.watch_speech_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // ---- 语音识别 (ASR) — the one that matters most now the offline model is gone.
                SectionLabel(stringResource(R.string.watch_speech_asr))
                EngineChoice(
                    useProvider = prefs.asrUseProvider,
                    onChange = { value -> onEdit { it.copy(asrUseProvider = value) } },
                )
                if (prefs.asrUseProvider) {
                    ProviderDropdown(
                        label = stringResource(R.string.watch_speech_provider),
                        selected = prefs.asrProviderName,
                        providers = providers,
                        onSelect = { name -> onEdit { it.copy(asrProviderName = name) } },
                    )
                    SpeechField(
                        label = stringResource(R.string.watch_speech_model),
                        value = prefs.asrModel,
                        placeholder = "gpt-4o-mini-transcribe",
                        onChange = { value -> onEdit { it.copy(asrModel = value) } },
                    )
                    SpeechField(
                        label = stringResource(R.string.watch_speech_language),
                        value = prefs.asrLanguage,
                        placeholder = "zh",
                        onChange = { value -> onEdit { it.copy(asrLanguage = value) } },
                    )
                    SectionLabel(stringResource(R.string.watch_speech_transport))
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        ChoiceChip(
                            label = stringResource(R.string.watch_speech_transport_chat),
                            selected = prefs.asrTransport != "transcriptions",
                            modifier = Modifier.weight(1f),
                            onClick = { onEdit { it.copy(asrTransport = "chat") } },
                        )
                        Spacer(Modifier.size(8.dp))
                        ChoiceChip(
                            label = stringResource(R.string.watch_speech_transport_transcriptions),
                            selected = prefs.asrTransport == "transcriptions",
                            modifier = Modifier.weight(1f),
                            onClick = { onEdit { it.copy(asrTransport = "transcriptions") } },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ---- 语音合成 (TTS)
                SectionLabel(stringResource(R.string.watch_speech_tts))
                EngineChoice(
                    useProvider = prefs.ttsUseProvider,
                    onChange = { value -> onEdit { it.copy(ttsUseProvider = value) } },
                )
                if (prefs.ttsUseProvider) {
                    ProviderDropdown(
                        label = stringResource(R.string.watch_speech_provider),
                        selected = prefs.ttsProviderName,
                        providers = providers,
                        onSelect = { name -> onEdit { it.copy(ttsProviderName = name) } },
                    )
                    SpeechField(
                        label = stringResource(R.string.watch_speech_model),
                        value = prefs.ttsModel,
                        placeholder = "tts-1",
                        onChange = { value -> onEdit { it.copy(ttsModel = value) } },
                    )
                    SpeechField(
                        label = stringResource(R.string.watch_speech_voice),
                        value = prefs.ttsVoice,
                        placeholder = "alloy",
                        onChange = { value -> onEdit { it.copy(ttsVoice = value) } },
                    )
                }

                // A provider engine with no model set silently falls back to the device engine on the
                // watch, which looks like "the setting didn't stick" — flag it here instead.
                val incomplete = (prefs.asrUseProvider && prefs.asrModel.isBlank()) ||
                    (prefs.ttsUseProvider && prefs.ttsModel.isBlank())
                if (incomplete) {
                    NoticeRow(
                        icon = Icons.Outlined.ErrorOutline,
                        text = stringResource(R.string.watch_speech_model_required),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineChoice(useProvider: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        ChoiceChip(
            label = stringResource(R.string.watch_speech_engine_device),
            selected = !useProvider,
            modifier = Modifier.weight(1f),
            onClick = { onChange(false) },
        )
        Spacer(Modifier.size(8.dp))
        ChoiceChip(
            label = stringResource(R.string.watch_speech_engine_provider),
            selected = useProvider,
            modifier = Modifier.weight(1f),
            onClick = { onChange(true) },
        )
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
    )
}

@Composable
private fun ProviderDropdown(
    label: String,
    selected: String,
    providers: List<String>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // An invisible tap target over the read-only field: ExposedDropdownMenuBox would do this for
        // us but drags in a fixed menu width that clips long provider names on a narrow phone.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (providers.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.watch_no_providers)) },
                    onClick = { open = false },
                )
            }
            providers.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onSelect(name); open = false },
                )
            }
        }
    }
}

@Composable
private fun SpeechField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun ConnectingStep(state: WatchSetupUiState) {
    val payload = state.payload ?: return
    HeroHeader(
        icon = Icons.Outlined.WifiTethering,
        title = stringResource(R.string.watch_connect_title),
        subtitle = stringResource(R.string.watch_connect_desc),
    )

    HotspotCard(ssid = payload.ssid, password = payload.password)

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.watch_searching, state.searchSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.watch_searching_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            // Only nag about the hotspot once the quiet path (same Wi-Fi already) has clearly failed.
            AnimatedVisibility(visible = state.searchSeconds >= 15) {
                Text(
                    stringResource(R.string.watch_searching_slow),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PushingStep(state: WatchSetupUiState) {
    HeroHeader(
        icon = Icons.Outlined.Watch,
        title = stringResource(R.string.watch_push_title),
        subtitle = state.watch?.hello?.name ?: "",
    )
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

@Composable
private fun DoneStep(state: WatchSetupUiState, onDone: () -> Unit, onAgain: () -> Unit) {
    val result = state.result
    HeroHeader(
        icon = Icons.Filled.Check,
        title = stringResource(R.string.watch_done_title),
        subtitle = state.watch?.hello?.name.orEmpty(),
    )
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SummaryRow(
                stringResource(R.string.watch_done_providers),
                (result?.providers ?: 0).toString(),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SummaryRow(
                stringResource(R.string.watch_done_models),
                (result?.models ?: 0).toString(),
            )
            state.watch?.let {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SummaryRow(stringResource(R.string.watch_done_address), it.host)
            }
        }
    }
    Text(
        stringResource(R.string.watch_done_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp),
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(stringResource(R.string.watch_done_button))
    }
    TextButton(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.watch_pair_another))
    }
}

@Composable
private fun FailedStep(
    state: WatchSetupUiState,
    onRetry: () -> Unit,
    onRescan: () -> Unit,
    onManual: (String) -> Unit,
) {
    var manualHost by rememberSaveable { mutableStateOf("") }
    var showManual by rememberSaveable { mutableStateOf(false) }

    HeroHeader(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.watch_failed_title),
        subtitle = errorText(state.error),
        tint = MaterialTheme.colorScheme.error,
    )

    state.payload?.let { HotspotCard(ssid = it.ssid, password = it.password) }

    if (state.payload != null) {
        FilledTonalButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.watch_retry))
        }
    }
    OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.watch_rescan))
    }

    if (state.payload != null) {
        TextButton(onClick = { showManual = !showManual }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.watch_manual_toggle))
        }
        AnimatedVisibility(visible = showManual) {
            Column {
                Text(
                    stringResource(R.string.watch_manual_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it },
                    label = { Text(stringResource(R.string.watch_manual_label)) },
                    placeholder = { Text("192.168.43.23") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    onClick = { onManual(manualHost) },
                    enabled = manualHost.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.watch_manual_connect))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- pieces

@Composable
private fun HotspotCard(ssid: String, password: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.watch_hotspot_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.watch_hotspot_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            CredentialRow(
                label = stringResource(R.string.watch_hotspot_ssid),
                value = ssid,
                onCopy = { clipboard.setText(AnnotatedString(ssid)) },
            )
            Spacer(Modifier.height(8.dp))
            CredentialRow(
                label = stringResource(R.string.watch_hotspot_password),
                value = password,
                onCopy = { clipboard.setText(AnnotatedString(password)) },
            )
            FilledTonalButton(
                onClick = { openHotspotSettings(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                Icon(Icons.Outlined.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.watch_open_hotspot_settings))
            }
        }
    }
}

@Composable
private fun CredentialRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.watch_copy),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun HeroHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun StepLine(index: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                index.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NoticeRow(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
private fun errorText(error: String?): String = when (error) {
    "bad_qr" -> stringResource(R.string.watch_error_bad_qr)
    "not_found" -> stringResource(R.string.watch_error_not_found)
    "push_failed" -> stringResource(R.string.watch_error_push)
    null -> ""
    else -> error
}

/**
 * Opens the tethering page directly where the ROM exposes it, falling back to the wireless settings
 * root. Android gives no public action for the hotspot screen and no API to configure it, so this is
 * as close to "one tap" as the platform allows.
 */
private fun openHotspotSettings(context: Context) {
    val candidates = listOf(
        Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.android.settings", "com.android.settings.TetherSettings"),
        ),
        Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
        ),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (opened) return
    }
}
