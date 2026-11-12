package com.cone.agent.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
/**
 * The remote area mirrors ConeCode's own palette (warm "Claude" theme), supporting both light and
 * dark — see `src/index.css` in the desktop project. The phone follows the **desktop's** resolved
 * theme (sent in the snapshot) so the two stay in sync live; before connecting it follows the phone's
 * system setting. Provided via [LocalRemotePalette].
 */
data class RemotePalette(
    val bg0: Color, val bg1: Color, val bg2: Color, val bg3: Color, val bg4: Color,
    val border: Color, val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val accentSoft: Color, val danger: Color, val amber: Color, val success: Color,
)

private val DarkPalette = RemotePalette(
    bg0 = Color(0xFF1A1915), bg1 = Color(0xFF14130F), bg2 = Color(0xFF242320), bg3 = Color(0xFF2E2D28),
    bg4 = Color(0xFF3A3833),
    border = Color(0xFF353330), text = Color(0xFFF0EDE8), muted = Color(0xFFA5A09A), faint = Color(0xFF706B65),
    accent = Color(0xFFDA7756), accentSoft = Color(0x26DA7756), danger = Color(0xFFE05A4A), amber = Color(0xFFD4993E),
    success = Color(0xFF57B37A),
)

private val LightPalette = RemotePalette(
    bg0 = Color(0xFFFAF6F1), bg1 = Color(0xFFF0EBE3), bg2 = Color(0xFFFFFFFF), bg3 = Color(0xFFEDE8E0),
    bg4 = Color(0xFFDDD7CE),
    border = Color(0xFFE8E2DA), text = Color(0xFF1A1612), muted = Color(0xFF6B6560), faint = Color(0xFF9B9590),
    accent = Color(0xFFC96442), accentSoft = Color(0x1AC96442), danger = Color(0xFFC43E2E), amber = Color(0xFFB8762B),
    success = Color(0xFF2D8A56),
)

internal val LocalRemotePalette = staticCompositionLocalOf { DarkPalette }

/** Which mirror surface is showing: the chat, the file browser, or the terminal. */
internal enum class RemoteTab { CHAT, CHANGES, FILES, TERMINAL }

/**
 * ConeAI sidebar entry that turns the phone into a remote control for a ConeCode desktop session.
 * A Codex-style pairing screen until connected, then a phone-proportioned mirror of the desktop chat
 * (messages, todos, approvals, conversations, model switching) driven over the HTTPS+SSE bridge.
 */
@Composable
fun RemoteScreen(
    onBack: () -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 回到远程页时把上次的会话接回来（连接本身活在应用级单例里，不会因为离开页面而消失）。
    LaunchedEffect(Unit) { viewModel.restore() }
    // 镜像画面里有电脑桌面的全部内容：连接期间禁止截屏与最近任务缩略图。
    val window = rememberWindow()
    DisposableEffect(state.showMirror) {
        if (state.showMirror) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // Once connected, follow the desktop's resolved theme so the mirror stays visually aligned.
    val desktopDark = state.snapshot?.theme == "dark"
    val palette = if (state.snapshot?.theme != null) {
        if (desktopDark) DarkPalette else LightPalette
    } else if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalRemotePalette provides palette) {
        Surface(modifier = Modifier.fillMaxSize(), color = palette.bg0) {
            if (state.showMirror) {
                MirrorScreen(state = state, viewModel = viewModel)
            } else {
                ConnectScreen(
                    state = state,
                    onConnect = { url, pw -> viewModel.connect(url, pw) },
                    onUnlock = viewModel::unlockSaved,
                    onClearSaved = viewModel::clearSaved,
                    onCancel = viewModel::disconnect,
                    onBack = onBack,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Connect screen (Codex-style pairing)
// ---------------------------------------------------------------------------

@Composable
private fun ConnectScreen(
    state: RemoteUiState,
    onConnect: (String, String?) -> Unit,
    onUnlock: () -> Unit,
    onClearSaved: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    var url by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    fun pw(): String? = password.ifBlank { null }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val bioAvailable = remember { RemoteBiometric.isAvailable(context) }
    val canBiometric = state.canBiometric && bioAvailable && activity != null
    val bioTitle = stringResource(R.string.remote_bio_title)
    val bioSubtitle = stringResource(R.string.remote_bio_subtitle)
    val unlock: () -> Unit = {
        if (activity != null) RemoteBiometric.prompt(activity, bioTitle, bioSubtitle, onSuccess = onUnlock)
    }
    // Offer the biometric unlock automatically once when arriving with a saved login.
    var bioPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canBiometric) {
        if (canBiometric && !bioPrompted && state.phase == RemotePhase.DISCONNECTED) {
            bioPrompted = true
            unlock()
        }
    }

    // When the desktop demands a password (e.g. right after scanning a public-mode QR),
    // pop a password dialog; the control UI only shows once the password is accepted.
    var showPwDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        if (state.phase == RemotePhase.NEEDS_PASSWORD) showPwDialog = true
    }

    val scanPrompt = stringResource(R.string.remote_scan_prompt)
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { onConnect(it, pw()) }
    }
    val launchScan = {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setOrientationLocked(true) // lock to the portrait capture activity below
                .setBeepEnabled(false)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
                .setPrompt(scanPrompt),
        )
    }
    val connecting = state.phase == RemotePhase.CONNECTING

    Column(modifier = Modifier.fillMaxSize().background(cx.bg0)) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = cx.text)
            }
            Text(stringResource(R.string.remote_title), color = cx.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = cx.border)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(cx.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Smartphone, contentDescription = null, tint = cx.accent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.remote_connect_title), color = cx.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.remote_connect_desc),
                color = cx.muted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(24.dp))

            // Returning user with a saved login → fingerprint/face unlock ("passkey").
            if (canBiometric) {
                PrimaryButton(
                    text = stringResource(R.string.remote_unlock),
                    onClick = unlock,
                    enabled = !connecting,
                    leading = { Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.remote_forget),
                    color = cx.faint,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClearSaved).padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.size(22.dp))
            }

            PrimaryButton(
                text = stringResource(R.string.remote_scan),
                onClick = launchScan,
                enabled = !connecting,
                leading = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
            )

            Spacer(Modifier.size(20.dp))
            OrDivider(stringResource(R.string.remote_or_manual))
            Spacer(Modifier.size(16.dp))

            DarkInput(
                value = url,
                onValueChange = { url = it },
                placeholder = stringResource(R.string.remote_url_placeholder),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                imeAction = ImeAction.Next,
            )
            Spacer(Modifier.size(10.dp))
            DarkInput(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.remote_password_optional),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                imeAction = ImeAction.Go,
                onImeAction = { if (url.isNotBlank()) onConnect(url, pw()) },
            )
            Spacer(Modifier.size(12.dp))
            SecondaryButton(
                text = stringResource(R.string.remote_connect_btn),
                onClick = { if (url.isNotBlank()) onConnect(url, pw()) },
                enabled = !connecting && url.isNotBlank(),
            )

            state.savedUrl?.takeIf { it.isNotBlank() && it != url }?.let { saved ->
                Spacer(Modifier.size(20.dp))
                RememberedRow(
                    label = RemoteEndpoint.parse(saved)?.display ?: saved,
                    enabled = !connecting,
                    onClick = { onConnect(saved, pw()) },
                )
            }

            Spacer(Modifier.size(20.dp))
            when {
                connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = cx.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(R.string.remote_connecting), color = cx.muted, fontSize = 13.sp)
                    Spacer(Modifier.size(14.dp))
                    Text(
                        stringResource(R.string.action_cancel),
                        color = cx.accent,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable(onClick = onCancel),
                    )
                }

                state.phase == RemotePhase.ERROR -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.remote_connect_failed), color = cx.danger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    state.errorMessage?.let {
                        Spacer(Modifier.size(4.dp))
                        Text(it, color = cx.faint, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showPwDialog) {
        val target = state.savedUrl ?: url
        PasswordDialog(
            label = state.endpointLabel ?: (RemoteEndpoint.parse(target)?.display ?: ""),
            phase = state.phase,
            onSubmit = { p -> if (target.isNotBlank()) onConnect(target, p) },
            onDismiss = { showPwDialog = false; onCancel() },
        )
    }
}

@Composable
private fun OrDivider(label: String) {
    val cx = LocalRemotePalette.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(color = cx.border, modifier = Modifier.weight(1f))
        Text(label, color = cx.faint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(color = cx.border, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RememberedRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    val cx = LocalRemotePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cx.bg2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = cx.muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.remote_last_connected), color = cx.faint, fontSize = 11.sp)
            Text(label, color = cx.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------
// Mirror screen (live ConeCode session, responsive phone / pad shells)
// ---------------------------------------------------------------------------

@Composable
private fun MirrorScreen(state: RemoteUiState, viewModel: RemoteViewModel) {
    val cx = LocalRemotePalette.current
    val snap = state.snapshot
    if (snap == null) {
        // Connected, but the first state frame hasn't arrived yet — show a brief loader
        // (with a way out) instead of a blank screen.
        Column(modifier = Modifier.fillMaxSize().background(cx.bg0)) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ConeCode", color = cx.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.remote_disconnect),
                    color = cx.danger,
                    fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { viewModel.disconnect() }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            HorizontalDivider(color = cx.border)
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = cx.accent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.remote_connecting), color = cx.muted, fontSize = 13.sp)
                }
            }
        }
        return
    }
    val layout = rememberRemoteLayout()
    val twoPane = rememberRemoteTwoPane()
    var showModelPicker by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(RemoteTab.CHAT) }
    val onPickModel: () -> Unit = { showModelPicker = true }

    when (layout) {
        RemoteLayout.PAD -> PadMirror(
            state = state,
            snapshot = snap,
            viewModel = viewModel,
            tab = tab,
            onTab = { tab = it },
            twoPane = twoPane,
            onPickModel = onPickModel,
        )
        RemoteLayout.PHONE -> PhoneMirror(
            state = state,
            snapshot = snap,
            viewModel = viewModel,
            tab = tab,
            onTab = { tab = it },
            onPickModel = onPickModel,
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = snap.models,
            selectedId = snap.model?.id,
            selectedProviderId = snap.model?.providerId,
            reasoningEffort = snap.reasoningEffort,
            onPick = { id, providerId -> viewModel.setModel(id, providerId); showModelPicker = false },
            onSetEffort = { viewModel.setReasoningEffort(it) },
            onDismiss = { showModelPicker = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Responsive shells: phone (bottom bar + drawer) vs pad (nav rail + optional
// second pane). Both share the same panes, so behaviour cannot drift.
// ---------------------------------------------------------------------------

/** The hosting window, so we can toggle FLAG_SECURE while the mirror is on screen. */
@Composable
private fun rememberWindow(): Window? {
    val context = LocalContext.current
    return remember(context) {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return@remember ctx.window
            ctx = ctx.baseContext
        }
        null
    }
}

/** 顶部标题始终是当前会话名，页别由下方标签条表达（与桌面一致）。 */
@Composable
private fun conversationTitle(snapshot: RemoteSnapshot): String =
    snapshot.conversations.firstOrNull { it.id == snapshot.activeConversationId }
        ?.title?.takeIf { it.isNotBlank() }
        ?: snapshot.conversations.firstOrNull()?.title?.takeIf { it.isNotBlank() }
        ?: "ConeCode"

private fun RemoteTab.icon(): ImageVector = when (this) {
    RemoteTab.CHAT -> Icons.Filled.ChatBubbleOutline
    RemoteTab.CHANGES -> Icons.Filled.Difference
    RemoteTab.FILES -> Icons.Filled.Folder
    RemoteTab.TERMINAL -> Icons.Filled.Terminal
}

private fun RemoteTab.labelRes(): Int = when (this) {
    RemoteTab.CHAT -> R.string.remote_tab_chat
    RemoteTab.CHANGES -> R.string.remote_changes
    RemoteTab.FILES -> R.string.remote_files
    RemoteTab.TERMINAL -> R.string.remote_terminal
}

/** 对话主体：消息流 + 待办/待确认浮层 + 输入框。手机与平板共用。 */
@Composable
private fun ChatPane(snapshot: RemoteSnapshot, viewModel: RemoteViewModel, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        MessageList(snapshot = snapshot, modifier = Modifier.weight(1f))
        Panels(snapshot = snapshot, viewModel = viewModel)
        Composer(
            viewModel = viewModel,
            rootPath = snapshot.rootPath,
            isStreaming = snapshot.isStreaming,
            onSend = viewModel::sendMessage,
            onStop = viewModel::stopGeneration,
        )
    }
}

/**
 * 桌面版式的外壳：顶部标题栏 + 工作台标签条，左侧会话栏（手机是抽屉，平板常驻）。
 * 这是对着桌面端 ConeCode 的 AppShell / WorkbenchTabs 做的：同样是「侧栏 + 标签页」，
 * 不再用手机常见的底部导航，两端看到的层级关系保持一致。
 */
@Composable
private fun MirrorHeader(
    title: String,
    modelName: String?,
    connected: Boolean,
    insecure: Boolean,
    tab: RemoteTab,
    changeCount: Int,
    onTab: (RemoteTab) -> Unit,
    onMenu: (() -> Unit)?,
    onPickModel: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    Column(modifier = Modifier.fillMaxWidth().background(cx.bg1)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onMenu != null) {
                IconButton(onClick = onMenu, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = stringResource(R.string.remote_conversations),
                        tint = cx.muted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (connected) cx.success else cx.amber),
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                title,
                color = cx.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onMenu != null) Spacer(Modifier.size(4.dp))
            if (insecure) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(cx.amber.copy(alpha = 0.18f))
                        .border(1.dp, cx.amber, RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, tint = cx.amber, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.remote_insecure_chip),
                        color = cx.amber,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.size(6.dp))
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(cx.bg3)
                    .border(1.dp, cx.border, RoundedCornerShape(8.dp))
                    .clickable(onClick = onPickModel)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (connected) cx.success else cx.faint),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    modelName ?: stringResource(R.string.remote_no_models),
                    color = cx.muted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(4.dp))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = cx.faint, modifier = Modifier.size(14.dp))
            }
        }
        WorkbenchTabStrip(tab = tab, changeCount = changeCount, onTab = onTab)
    }
}

/** 桌面 WorkbenchTabs 的迷你版：图标 + 名称 + 待确认数量角标 + 选中下划线。 */
@Composable
private fun WorkbenchTabStrip(tab: RemoteTab, changeCount: Int, onTab: (RemoteTab) -> Unit) {
    val cx = LocalRemotePalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            RemoteTab.values().forEach { entry ->
                val active = tab == entry
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .clickable { onTab(entry) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            entry.icon(),
                            contentDescription = null,
                            tint = if (active) cx.accent else cx.faint,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(
                            stringResource(entry.labelRes()),
                            color = if (active) cx.text else cx.muted,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                        if (entry == RemoteTab.CHANGES && changeCount > 0) {
                            Spacer(Modifier.size(4.dp))
                            Text(
                                changeCount.toString(),
                                color = cx.accent,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(cx.accentSoft)
                                    .padding(horizontal = 4.dp, vertical = 0.5.dp),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (active) cx.accent else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(color = cx.border)
    }
}

/** 标签页内容：两端复用，只有文件页在宽屏下拆成两栏。 */
@Composable
private fun RemoteTabContent(
    tab: RemoteTab,
    snapshot: RemoteSnapshot,
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
    terminal: TerminalSession,
) {
    val cx = LocalRemotePalette.current
    var selectedFile by remember(snapshot.rootPath) { mutableStateOf<String?>(null) }
    Box(modifier = modifier.fillMaxSize()) {
        when (tab) {
            RemoteTab.CHAT -> ChatPane(snapshot, viewModel)
            RemoteTab.CHANGES -> ChangesPage(snapshot, viewModel)
            RemoteTab.FILES -> Row(modifier = Modifier.fillMaxSize()) {
                if (twoPane) {
                    FileBrowser(
                        viewModel = viewModel,
                        rootPath = snapshot.rootPath,
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                        onPick = { selectedFile = it },
                    )
                    VerticalDivider(color = cx.border)
                    val file = selectedFile
                    if (file != null) {
                        FileViewer(
                            viewModel = viewModel,
                            path = file,
                            onBack = { selectedFile = null },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        FilePanePlaceholder(Modifier.weight(1f))
                    }
                } else {
                    FileBrowser(viewModel, snapshot.rootPath, Modifier.fillMaxSize())
                }
            }
            RemoteTab.TERMINAL -> TerminalView(viewModel, snapshot.rootPath, terminal, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PhoneMirror(
    state: RemoteUiState,
    snapshot: RemoteSnapshot,
    viewModel: RemoteViewModel,
    tab: RemoteTab,
    onTab: (RemoteTab) -> Unit,
    onPickModel: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val terminal = rememberTerminalSession()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = cx.bg1, modifier = Modifier.fillMaxWidth(0.82f)) {
                ConversationSidebar(
                    snapshot = snapshot,
                    endpointLabel = state.endpointLabel,
                    onSwitch = { id ->
                        viewModel.switchConversation(id)
                        onTab(RemoteTab.CHAT)
                        scope.launch { drawerState.close() }
                    },
                    onNew = {
                        viewModel.newConversation()
                        onTab(RemoteTab.CHAT)
                        scope.launch { drawerState.close() }
                    },
                    onDelete = { id -> viewModel.deleteConversation(id) },
                    onDisconnect = {
                        scope.launch { drawerState.close() }
                        viewModel.disconnect()
                    },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().background(cx.bg0)) {
            MirrorHeader(
                title = conversationTitle(snapshot),
                modelName = snapshot.model?.name,
                connected = state.phase == RemotePhase.CONNECTED,
                insecure = state.insecure,
                tab = tab,
                changeCount = snapshot.changes.count { it.status == "pending" },
                onTab = onTab,
                onMenu = { scope.launch { drawerState.open() } },
                onPickModel = onPickModel,
            )
            RemoteTabContent(tab, snapshot, viewModel, Modifier.weight(1f), terminal = terminal)
        }
    }
}

@Composable
private fun PadMirror(
    state: RemoteUiState,
    snapshot: RemoteSnapshot,
    viewModel: RemoteViewModel,
    tab: RemoteTab,
    onTab: (RemoteTab) -> Unit,
    twoPane: Boolean,
    onPickModel: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    val terminal = rememberTerminalSession()
    Row(modifier = Modifier.fillMaxSize().background(cx.bg0)) {
        Box(modifier = Modifier.width(276.dp).fillMaxHeight()) {
            ConversationSidebar(
                snapshot = snapshot,
                endpointLabel = state.endpointLabel,
                onSwitch = {
                    viewModel.switchConversation(it)
                    onTab(RemoteTab.CHAT)
                },
                onNew = {
                    viewModel.newConversation()
                    onTab(RemoteTab.CHAT)
                },
                onDelete = { viewModel.deleteConversation(it) },
                onDisconnect = { viewModel.disconnect() },
            )
        }
        VerticalDivider(color = cx.border)
        Column(modifier = Modifier.weight(1f)) {
            MirrorHeader(
                title = conversationTitle(snapshot),
                modelName = snapshot.model?.name,
                connected = state.phase == RemotePhase.CONNECTED,
                insecure = state.insecure,
                tab = tab,
                changeCount = snapshot.changes.count { it.status == "pending" },
                onTab = onTab,
                onMenu = null,
                onPickModel = onPickModel,
            )
            RemoteTabContent(tab, snapshot, viewModel, Modifier.weight(1f), twoPane = twoPane, terminal = terminal)
        }
    }
}

/**
 * 左侧会话栏，等同桌面 Sidebar：品牌行 + 新对话、会话分组标题、会话列表、底部连接信息。
 * 手机放进抽屉，平板直接常驻。
 */
@Composable
private fun ConversationSidebar(
    snapshot: RemoteSnapshot,
    endpointLabel: String?,
    onSwitch: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cx = LocalRemotePalette.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cx.bg1)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(cx.accent),
            )
            Spacer(Modifier.size(7.dp))
            Text(
                "CONECODE",
                color = cx.faint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(cx.accentSoft)
                    .border(1.dp, cx.accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onNew)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = cx.accent, modifier = Modifier.size(13.dp))
                Spacer(Modifier.size(4.dp))
                Text(stringResource(R.string.remote_new_chat), color = cx.accent, fontSize = 11.5.sp)
            }
        }
        Spacer(Modifier.size(12.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = cx.faint, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.remote_conversations),
                color = cx.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                snapshot.conversations.size.toString(),
                color = cx.faint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        ConversationList(
            snapshot = snapshot,
            onSwitch = onSwitch,
            onDelete = onDelete,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider(color = cx.border, modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .clickable(onClick = onDisconnect)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.LinkOff, contentDescription = null, tint = cx.danger, modifier = Modifier.size(15.dp))
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.remote_disconnect), color = cx.danger, fontSize = 12.5.sp)
                endpointLabel?.let {
                    Text(
                        it,
                        color = cx.faint,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 会话列表：桌面侧栏的会话行样式，抽屉与平板常驻侧栏共用。 */
@Composable
private fun ConversationList(
    snapshot: RemoteSnapshot,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cx = LocalRemotePalette.current
    var pendingDelete by remember { mutableStateOf<RemoteConversation?>(null) }
    Column(modifier = modifier) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(snapshot.conversations, key = { it.id }) { conv ->
                val active = conv.id == snapshot.activeConversationId
                val running = conv.isRunning || conv.id == snapshot.runningConversationId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) cx.bg3 else Color.Transparent)
                        .clickable { onSwitch(conv.id) }
                        .padding(start = 9.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 桌面用左侧 2dp 竖条标记当前会话。
                    Box(
                        modifier = Modifier
                            .size(width = 2.dp, height = 18.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (active) cx.accent else Color.Transparent),
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (running) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(cx.accent),
                                )
                                Spacer(Modifier.size(6.dp))
                            }
                            Text(
                                conv.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.remote_new_chat),
                                color = if (active) cx.text else cx.muted,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (conv.changedFiles.isNotEmpty()) {
                            Spacer(Modifier.size(2.dp))
                            Text(
                                conv.changedFiles.joinToString(" · "),
                                color = cx.faint,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { pendingDelete = conv }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = stringResource(R.string.cd_delete),
                            tint = cx.faint,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.delete_conv_title),
            message = stringResource(R.string.delete_conv_msg),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                onDelete(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 变更页：桌面 ConeCode 的变更面板整页搬过来——待办清单、待确认变更（可单条 / 批量批准），
 * 以及已应用、已撤回、失败的历史变更。此前手机上只能看到待确认的那几条。
 */
@Composable
private fun ChangesPage(
    snapshot: RemoteSnapshot,
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    val cx = LocalRemotePalette.current
    val pending = remember(snapshot.changes) { snapshot.changes.filter { it.status == "pending" } }
    val history = remember(snapshot.changes) { snapshot.changes.filter { it.status != "pending" } }

    if (snapshot.todos.isEmpty() && pending.isEmpty() && history.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Difference, contentDescription = null, tint = cx.faint, modifier = Modifier.size(30.dp))
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.remote_no_changes), color = cx.muted, fontSize = 13.sp)
            }
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (snapshot.todos.isNotEmpty()) TodoPanel(snapshot.todos)
        if (pending.isNotEmpty()) ApprovalPanel(pending, viewModel)
        if (history.isNotEmpty()) {
            Text(
                text = stringResource(R.string.remote_changes_history, history.size),
                color = cx.faint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            ) {
                items(history, key = { it.id.ifBlank { it.filePath + it.status } }) { change ->
                    ChangeHistoryCard(change)
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 已处理的变更（只读，带状态）。 */
@Composable
private fun ChangeHistoryCard(change: RemoteChange) {
    val cx = LocalRemotePalette.current
    val isCommand = change.filePath == "[Command]"
    val label = if (isCommand) change.newCode.orEmpty() else fileName(change.filePath)
    val kind = if (change.kind == "exec") "Run" else (change.kind ?: "edit")
    val statusColor = when (change.status) {
        "applied" -> cx.accent
        "failed" -> cx.danger
        else -> cx.muted
    }
    val statusLabel = when (change.status) {
        "applied" -> stringResource(R.string.remote_change_applied)
        "reverted" -> stringResource(R.string.remote_change_reverted)
        "failed" -> stringResource(R.string.remote_change_failed)
        else -> change.status
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cx.bg2)
            .border(1.dp, cx.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                kind,
                color = cx.muted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(cx.bg3)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                label,
                color = cx.text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Text(statusLabel, color = statusColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (!isCommand && change.description?.isNotBlank() == true) {
            Spacer(Modifier.size(5.dp))
            Text(
                change.description,
                color = cx.muted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FilePanePlaceholder(modifier: Modifier = Modifier) {
    val cx = LocalRemotePalette.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.remote_pick_file), color = cx.faint, fontSize = 13.sp)
    }
}

@Composable
private fun MessageList(snapshot: RemoteSnapshot, modifier: Modifier = Modifier) {
    val cx = LocalRemotePalette.current
    // Keep tool-result turns in the transcript: they are the desktop's evidence of
    // what a native tool call returned, while local notices remain hidden.
    val messages = remember(snapshot.messages) {
        snapshot.messages.filter { !it.isLocalNotice }
    }
    val listState = rememberLazyListState()
    val streamingLen = snapshot.streamingContent?.length ?: 0
    val itemCount = messages.size + if (snapshot.isStreaming) 1 else 0

    LaunchedEffect(messages.size, streamingLen, snapshot.isStreaming) {
        if (itemCount <= 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= itemCount - 2) listState.animateScrollToItem(itemCount - 1)
    }

    if (messages.isEmpty() && !snapshot.isStreaming) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.remote_no_messages), color = cx.muted, fontSize = 14.sp)
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
    ) {
        items(messages, key = { it.id }) { message -> MessageRow(message) }
        if (snapshot.isStreaming) {
            item(key = "__streaming__") { StreamingRow(snapshot) }
        }
    }
}

@Composable
private fun MessageRow(message: RemoteMessage) {
    val cx = LocalRemotePalette.current
    if (message.role == "user") {
        val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.82f).dp
        val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubble)
                    .clip(shape)
                    .background(cx.bg2)
                    .border(1.dp, cx.border, shape)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                Text(proseAnnotated(message.content, cx.bg3), color = cx.text, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
    } else {
        val clipboard = LocalClipboardManager.current
        val tokensLabel = stringResource(R.string.remote_tokens)
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            if (message.toolCalls.isNotEmpty()) {
                message.toolCalls.forEach { call -> ToolStatusLine(call.name, active = false) }
            }
            message.toolName?.takeIf { it.isNotBlank() }?.let { ToolStatusLine(it, active = false) }
            if (!message.reasoningContent.isNullOrBlank()) {
                Text(
                    message.reasoningContent.take(700),
                    color = cx.faint,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (message.content.isNotBlank()) RemoteContent(message.content)
            if (message.isToolResult && message.content.isNotBlank()) {
                Text("tool result", color = cx.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            // Footer: copy button + thinking time + token usage.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { clipboard.setText(AnnotatedString(message.content)) }
                        .padding(4.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.remote_copy), tint = cx.faint, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.weight(1f))
                val meta = buildList {
                    message.thinkingTime?.let { if (it > 0) add(formatThinkingMs(it)) }
                    message.tokensUsed?.let { if (it > 0) add("$it $tokensLabel") }
                }.joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Text(meta, color = cx.faint, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ToolStatusLine(name: String, active: Boolean) {
    val cx = LocalRemotePalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            CircularProgressIndicator(strokeWidth = 1.5.dp, color = cx.accent, modifier = Modifier.size(13.dp))
        } else {
            Icon(Icons.Filled.Check, contentDescription = null, tint = cx.faint, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.size(6.dp))
        Text(name, color = if (active) cx.accent else cx.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "2.3s" / "1.4m" for a thinking-time duration in milliseconds. */
private fun formatThinkingMs(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds < 60) "%.1fs".format(seconds) else "%.1fm".format(seconds / 60)
}

@Composable
private fun StreamingRow(snapshot: RemoteSnapshot) {
    val cx = LocalRemotePalette.current
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column {
            val thinking = snapshot.streamingReasoningContent
            snapshot.streamingToolName?.takeIf { it.isNotBlank() }?.let { tool ->
                ToolStatusLine(tool, active = true)
            }
            if (snapshot.streamingStatus == "thinking" && !thinking.isNullOrBlank()) {
                Text(
                    thinking.take(700),
                    color = cx.faint,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            val content = snapshot.streamingContent.orEmpty()
            if (content.isNotBlank()) RemoteContent(content)
            BlinkingCursor()
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val cx = LocalRemotePalette.current
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(520), repeatMode = RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(width = 7.dp, height = 15.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(cx.accent),
    )
}

// ---------------------------------------------------------------------------
// Sticky panels: todos + pending approvals
// ---------------------------------------------------------------------------

@Composable
private fun Panels(snapshot: RemoteSnapshot, viewModel: RemoteViewModel) {
    val pending = remember(snapshot.changes) { snapshot.changes.filter { it.status == "pending" } }
    if (snapshot.todos.isEmpty() && pending.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        if (snapshot.todos.isNotEmpty()) TodoPanel(snapshot.todos)
        if (pending.isNotEmpty()) ApprovalPanel(pending, viewModel)
    }
}

/**
 * 待办清单：对齐桌面 TodoList —— 折叠状态下就是一行「待办 · 当前任务 + 进度条 + 3/5」，
 * 展开才列出全部条目，不再一直占着聊天区域。
 */
@Composable
private fun TodoPanel(todos: List<RemoteTodo>) {
    val cx = LocalRemotePalette.current
    var expanded by remember { mutableStateOf(false) }
    val done = todos.count { it.status == "completed" }
    val total = todos.size
    val allDone = done == total
    val current = todos.firstOrNull { it.status == "in_progress" } ?: todos.firstOrNull { it.status != "completed" }
    val fraction = if (total == 0) 0f else done.toFloat() / total

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cx.bg2)
            .border(1.dp, cx.border, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = cx.muted,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.size(4.dp))
            Icon(Icons.Filled.Checklist, contentDescription = null, tint = cx.accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(5.dp))
            Text(
                stringResource(R.string.remote_todos),
                color = cx.muted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.size(5.dp))
            Text("·", color = cx.border, fontSize = 11.5.sp)
            Spacer(Modifier.size(5.dp))
            Text(
                if (allDone) stringResource(R.string.remote_all_done) else current?.content.orEmpty(),
                color = if (allDone) cx.success else cx.muted,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(cx.bg4),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (allDone) cx.success else cx.accent),
                    )
                }
            }
            Spacer(Modifier.size(7.dp))
            Text(
                "$done/$total",
                color = if (allDone) cx.success else cx.muted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (expanded) {
            HorizontalDivider(color = cx.border)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 148.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                todos.forEach { todo ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        val tint = when (todo.status) {
                            "completed" -> cx.success
                            "in_progress" -> cx.accent
                            else -> cx.faint
                        }
                        val icon = when (todo.status) {
                            "completed" -> Icons.Filled.CheckCircle
                            "in_progress" -> Icons.Filled.Autorenew
                            else -> Icons.Filled.RadioButtonUnchecked
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(12.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(
                            todo.content,
                            color = when (todo.status) {
                                "completed" -> cx.faint
                                "in_progress" -> cx.text
                                else -> cx.muted
                            },
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalPanel(pending: List<RemoteChange>, viewModel: RemoteViewModel) {
    val cx = LocalRemotePalette.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cx.bg0)
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Difference, contentDescription = null, tint = cx.amber, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(6.dp))
            Text(
                stringResource(R.string.remote_pending_changes, pending.size),
                color = cx.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            if (pending.size > 1) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(onClick = viewModel::approveAll)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(stringResource(R.string.remote_approve_all, pending.size), color = cx.accent, fontSize = 11.sp)
                }
                Spacer(Modifier.size(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable(onClick = viewModel::rejectAll)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(stringResource(R.string.remote_reject_all), color = cx.danger, fontSize = 11.sp)
                }
            }
        }
        pending.forEach { change -> ChangeCard(change, viewModel) }
    }
}

@Composable
private fun ChangeCard(change: RemoteChange, viewModel: RemoteViewModel) {
    val cx = LocalRemotePalette.current
    val isCommand = change.filePath == "[Command]"
    val label = if (isCommand) change.newCode.orEmpty() else fileName(change.filePath)
    val kind = if (change.kind == "exec") "Run" else (change.kind ?: "edit")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cx.bg2)
            .border(1.dp, cx.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                kind,
                color = cx.muted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(cx.bg3)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                label,
                color = cx.text,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = if (isCommand) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            ActionButton(
                text = stringResource(R.string.remote_approve),
                kind = ButtonKind.APPROVE,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.approve(change.id) },
            )
            Spacer(Modifier.size(8.dp))
            ActionButton(
                text = stringResource(R.string.remote_reject),
                kind = ButtonKind.REJECT,
                modifier = Modifier.weight(1f),
                onClick = { viewModel.reject(change.id) },
            )
        }
    }
}

private enum class ButtonKind { APPROVE, REJECT }

@Composable
private fun ActionButton(text: String, kind: ButtonKind, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cx = LocalRemotePalette.current
    val bg = if (kind == ButtonKind.APPROVE) cx.accentSoft else cx.bg3
    val borderColor = if (kind == ButtonKind.APPROVE) cx.accent else cx.border
    val fg = if (kind == ButtonKind.APPROVE) cx.accent else cx.danger
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------
// Composer
// ---------------------------------------------------------------------------

@Composable
private fun Composer(
    viewModel: RemoteViewModel,
    rootPath: String?,
    isStreaming: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    val canSend = text.isNotBlank()

    // Upload a phone file to the desktop, then insert its saved path as context.
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                uploading = true
                val name = queryDisplayName(context, uri)
                val b64 = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
                        }
                    }.getOrNull()
                }
                val path = if (b64 != null) viewModel.uploadFile(name, b64) else null
                uploading = false
                if (path != null) text = appendRef(text, path)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cx.bg0)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
    ) {
        // 桌面在输入框上方显示当前工作目录，这里保持一致。
        rootPath?.let { path ->
            Row(
                modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = cx.faint, modifier = Modifier.size(11.dp))
                Spacer(Modifier.size(4.dp))
                Text(
                    path,
                    color = cx.faint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(cx.bg2)
                .border(1.dp, cx.border, RoundedCornerShape(20.dp))
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(cx.bg3)
                        .clickable { showAttach = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (uploading) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = cx.accent, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.remote_attach), tint = cx.muted, modifier = Modifier.size(18.dp))
                    }
                }
                DropdownMenu(expanded = showAttach, onDismissRequest = { showAttach = false }, containerColor = cx.bg2) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_upload), color = cx.text, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null, tint = cx.muted, modifier = Modifier.size(17.dp)) },
                        onClick = {
                            showAttach = false
                            uploadLauncher.launch("*/*")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remote_pick_desktop_file), color = cx.text, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, tint = cx.muted, modifier = Modifier.size(17.dp)) },
                        onClick = {
                            showAttach = false
                            showPicker = true
                        },
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
            DarkInput(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.remote_message_hint),
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                singleLine = false,
            )
            Spacer(Modifier.size(6.dp))
            val bg = when {
                isStreaming -> cx.danger
                canSend -> cx.accent
                else -> cx.bg3
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(bg)
                    .clickable(enabled = isStreaming || canSend) {
                        if (isStreaming) {
                            onStop()
                        } else {
                            onSend(text)
                            text = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isStreaming) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                    contentDescription = if (isStreaming) stringResource(R.string.remote_stop) else stringResource(R.string.remote_send),
                    tint = if (isStreaming || canSend) Color.White else cx.faint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (showPicker) {
        Dialog(onDismissRequest = { showPicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(modifier = Modifier.fillMaxSize().background(cx.bg0)) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showPicker = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = cx.text)
                    }
                    Text(stringResource(R.string.remote_pick_desktop_file), color = cx.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = cx.border)
                FileBrowser(
                    viewModel = viewModel,
                    rootPath = rootPath,
                    modifier = Modifier.weight(1f),
                    onPick = { p -> text = appendRef(text, p); showPicker = false },
                )
            }
        }
    }
}

/** Append a file reference (`@path`) to the composer text so the agent reads it as context. */
private fun appendRef(text: String, path: String): String {
    val prefix = if (text.isBlank()) "" else text.trimEnd() + " "
    return "$prefix@$path "
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    var name = "upload.bin"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { name = it }
            }
        }
    }
    return name
}

// ---------------------------------------------------------------------------
// Model picker
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    models: List<RemoteModel>,
    selectedId: String?,
    selectedProviderId: String?,
    reasoningEffort: String?,
    onPick: (String, String?) -> Unit,
    onSetEffort: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    // Keep same-id models from different providers as distinct selectable entries.
    val unique = remember(models) { models.distinctBy { "${it.providerId.orEmpty()}:${it.id}" } }
    // Cap the model list so the sheet stays a reasonable height and scrolls internally.
    val listMax = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = cx.bg1,
        dragHandle = { BottomSheetDefaults.DragHandle(color = cx.faint) },
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            // Reasoning effort (思考能力).
            Text(
                stringResource(R.string.remote_thinking),
                color = cx.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                EffortChip("low", stringResource(R.string.remote_effort_low), reasoningEffort, Modifier.weight(1f), onSetEffort)
                Spacer(Modifier.size(8.dp))
                EffortChip("medium", stringResource(R.string.remote_effort_medium), reasoningEffort, Modifier.weight(1f), onSetEffort)
                Spacer(Modifier.size(8.dp))
                EffortChip("high", stringResource(R.string.remote_effort_high), reasoningEffort, Modifier.weight(1f), onSetEffort)
            }

            HorizontalDivider(color = cx.border)
            Text(
                stringResource(R.string.remote_pick_model),
                color = cx.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp),
            )
            if (unique.isEmpty()) {
                Text(
                    stringResource(R.string.remote_no_models),
                    color = cx.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = listMax)) {
                    items(unique, key = { "${it.providerId.orEmpty()}:${it.id}" }) { model ->
                        val selected = model.id == selectedId && (selectedProviderId == null || model.providerId == selectedProviderId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(model.id, model.providerId) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    model.name.takeIf { it.isNotBlank() } ?: model.id,
                                    color = if (selected) cx.accent else cx.text,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (model.name.isNotBlank() && model.id != model.name) {
                                    Text(
                                        model.id,
                                        color = cx.faint,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = cx.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffortChip(value: String, label: String, current: String?, modifier: Modifier, onSet: (String) -> Unit) {
    val cx = LocalRemotePalette.current
    val selected = current == value
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) cx.accentSoft else cx.bg2)
            .border(1.dp, if (selected) cx.accent else cx.border, RoundedCornerShape(9.dp))
            .clickable { onSet(value) }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) cx.accent else cx.text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ---------------------------------------------------------------------------
// Shared widgets
// ---------------------------------------------------------------------------

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cx.bg2)
                .border(1.dp, cx.border, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(title, color = cx.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(8.dp))
            Text(message, color = cx.muted, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.size(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    stringResource(R.string.action_cancel),
                    color = cx.muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    confirmLabel,
                    color = cx.danger,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onConfirm).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    label: String,
    phase: RemotePhase,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cx = LocalRemotePalette.current
    var pw by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    val connecting = phase == RemotePhase.CONNECTING
    val wrong = tried && phase == RemotePhase.NEEDS_PASSWORD
    val canSubmit = pw.isNotBlank() && !connecting
    val submit = { if (canSubmit) { tried = true; onSubmit(pw) } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cx.bg2)
                .border(1.dp, cx.border, RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(stringResource(R.string.remote_password_title), color = cx.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (label.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(label, color = cx.faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.size(14.dp))
            DarkInput(
                value = pw,
                onValueChange = { pw = it },
                placeholder = stringResource(R.string.remote_password_field),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                mask = true,
                imeAction = ImeAction.Go,
                onImeAction = submit,
            )
            if (wrong) {
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.remote_need_password), color = cx.danger, fontSize = 12.sp)
            }
            Spacer(Modifier.size(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (connecting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = cx.accent, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.action_cancel),
                    color = cx.muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.remote_connect_btn),
                    color = if (canSubmit) cx.accent else cx.faint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = canSubmit) { submit() }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    val cx = LocalRemotePalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) cx.accent else cx.bg3)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.let { it(); Spacer(Modifier.size(8.dp)) }
            Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val cx = LocalRemotePalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cx.bg2)
            .border(1.dp, cx.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) cx.text else cx.faint, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun DarkInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    mask: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {},
) {
    val cx = LocalRemotePalette.current
    Box(
        modifier = modifier
            .heightIn(min = 44.dp, max = 120.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(cx.bg2)
            .border(1.dp, cx.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = cx.text, fontSize = 16.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(cx.accent),
            singleLine = singleLine,
            visualTransformation = if (mask) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onGo = { onImeAction() },
                onDone = { onImeAction() },
                onSend = { onImeAction() },
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = cx.faint, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Content rendering — ported from electron/remote/mobileClient.ts
// ---------------------------------------------------------------------------

/** Renders assistant content: prose, fenced code blocks, and ```json action blocks as tool cards. */
@Composable
private fun RemoteContent(text: String, modifier: Modifier = Modifier) {
    val cx = LocalRemotePalette.current
    if (text.isBlank()) return
    val segments = remember(text) { splitFences(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            when (segment) {
                is Seg.Prose -> if (segment.text.isNotBlank()) {
                    Text(proseAnnotated(segment.text, cx.bg2), color = cx.text, fontSize = 15.sp, lineHeight = 22.sp)
                }
                is Seg.Fence -> {
                    val tool = toolCard(segment.lang, segment.code)
                    if (tool != null) ToolCard(tool) else CodeBlock(segment.code)
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolLine) {
    val cx = LocalRemotePalette.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("›", color = cx.faint, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Spacer(Modifier.size(8.dp))
        Text(tool.verb, color = cx.accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        if (tool.detail.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(
                tool.detail,
                color = cx.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val cx = LocalRemotePalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cx.bg1)
            .border(1.dp, cx.border, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(code.trimEnd('\n'), color = cx.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, softWrap = false)
    }
}

private sealed interface Seg {
    data class Prose(val text: String) : Seg
    data class Fence(val lang: String, val code: String) : Seg
}

private data class ToolLine(val verb: String, val detail: String)

private val VERB = mapOf(
    "read_file" to "Read", "list_dir" to "List", "search" to "Search", "glob" to "Glob",
    "edit_file" to "Edit", "create_file" to "Create", "create_dir" to "Make dir", "delete" to "Delete",
    "rename" to "Rename", "copy" to "Copy", "exec" to "Run", "open_app" to "Open app", "open_path" to "Open",
    "system_info" to "System", "web_fetch" to "Fetch", "web_search" to "Web search", "mcp_call" to "MCP",
    "update_todos" to "Plan", "ask_user" to "Question", "spawn_agent" to "Sub-agent",
    "git_status" to "Git status", "git_diff" to "Git diff",
)

private fun splitFences(text: String): List<Seg> {
    val fence = "```"
    val out = ArrayList<Seg>()
    var idx = 0
    while (true) {
        val start = text.indexOf(fence, idx)
        if (start == -1) {
            out.add(Seg.Prose(text.substring(idx)))
            break
        }
        if (start > idx) out.add(Seg.Prose(text.substring(idx, start)))
        val afterStart = start + 3
        val nl = text.indexOf('\n', afterStart)
        val lang = (if (nl == -1) text.substring(afterStart) else text.substring(afterStart, nl)).trim()
        val bodyStart = if (nl == -1) text.length else nl + 1
        val end = text.indexOf(fence, bodyStart)
        if (end == -1) {
            out.add(Seg.Fence(lang, text.substring(bodyStart)))
            break
        }
        out.add(Seg.Fence(lang, text.substring(bodyStart, end)))
        idx = end + 3
    }
    return out
}

private fun toolCard(lang: String, code: String): ToolLine? {
    if (lang.lowercase() != "json") return null
    val obj = runCatching { RemoteJson.parseToJsonElement(code.trim()).jsonObject }.getOrNull() ?: return null
    val action = (obj["action"] as? JsonPrimitive)?.contentOrNull ?: return null
    val verb = VERB[action] ?: action
    val detail = listOf("path", "command", "query", "url", "pattern", "name")
        .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        ?: ""
    return ToolLine(verb, detail)
}

/** Inline `**bold**` and `` `code` `` only — the subset the mobile mirror renders. */
private fun proseAnnotated(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        when {
            rest.startsWith("**") -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append("**"); i += 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append('`'); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

private fun fileName(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i >= 0) path.substring(i + 1) else path
}
