package com.cone.agent.ui.screens.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhonelinkRing
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cone.agent.R
import com.cone.agent.assistant.SpeechController
import com.cone.agent.assistant.VoskSpeechEngine
import com.cone.agent.code.CodeFolderPickerDialog
import com.cone.agent.code.CodeHelpDialog
import com.cone.agent.code.CodeHistorySheet
import com.cone.agent.code.CodeNewProjectDialog
import com.cone.agent.code.CodeOverlays
import com.cone.agent.code.CodePage
import com.cone.agent.code.CodePane
import com.cone.agent.code.CodeViewModel
import com.cone.agent.code.CodeWorkflowMemorySheet
import com.cone.agent.core.Constants
import com.cone.agent.domain.model.ConversationMode
import com.cone.agent.domain.model.ConversationSummary
import com.cone.agent.domain.model.TaskStatus
import com.cone.agent.domain.model.UiMessage
import com.cone.agent.ui.SystemActions
import com.cone.agent.ui.components.ConeInputPill
import com.cone.agent.ui.components.MessageBubble
import com.cone.agent.ui.components.ModelSwitcher
import com.cone.agent.ui.components.humanAction
import com.cone.agent.ui.theme.ConeShapes
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    systemActions: SystemActions,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenWatch: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val agentMessages by viewModel.agentMessages.collectAsStateWithLifecycle()
    val askMessages by viewModel.askMessages.collectAsStateWithLifecycle()
    val state by viewModel.agentState.collectAsStateWithLifecycle()
    val label by viewModel.selectedLabel.collectAsStateWithLifecycle()
    val environment by viewModel.environment.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val asking by viewModel.asking.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsStateWithLifecycle()
    val cumulativeTokens by viewModel.cumulativeTokens.collectAsStateWithLifecycle()
    val incognitoAsk by viewModel.incognitoAsk.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val allModels by viewModel.allModels.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModel.collectAsStateWithLifecycle()

    // 写代码 is the third page of this screen, not a destination of its own, so its view model lives
    // here alongside the chat one and its content is composed straight into the pager.
    val codeViewModel: CodeViewModel = hiltViewModel()
    val codeProject by codeViewModel.current.collectAsStateWithLifecycle()
    val codeRunning by codeViewModel.running.collectAsStateWithLifecycle()
    val codePane by codeViewModel.pane.collectAsStateWithLifecycle()
    val codeAwaitingAnswer by codeViewModel.awaitingAnswer.collectAsStateWithLifecycle()
    val codeNotice by codeViewModel.notice.collectAsStateWithLifecycle()
    val codeHelpVisible by codeViewModel.showHelp.collectAsStateWithLifecycle()
    var showCodeHistory by remember { mutableStateOf(false) }
    var showCodeNewProject by remember { mutableStateOf(false) }
    var showCodeFolder by remember { mutableStateOf(false) }
    var showCodeHabits by remember { mutableStateOf(false) }

    // Used to drop the soft keyboard (and input focus) as soon as a question is sent.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Saveable so the draft + attachments survive the activity being recreated / paused while the
    // user authorizes screen capture (MediaProjection consent) or visits the permissions screen.
    var input by rememberSaveable { mutableStateOf("") }
    val imageProgress by viewModel.imageProgress.collectAsStateWithLifecycle()
    var attachedImage by rememberSaveable { mutableStateOf<Uri?>(null) }
    var attachedFile by rememberSaveable { mutableStateOf<Uri?>(null) }
    var attachedFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var listening by remember { mutableStateOf(false) }
    // Text present when the mic was turned on: voice output is appended AFTER it (instead of wiping
    // the field), so anything you typed is kept and you can keep editing while the mic is on.
    var voiceAnchor by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Deliberately NOT rememberSaveable: the drawer must always start closed when this screen is
    // (re)entered. Persisting an "open" state across the back stack / process death restores a broken,
    // screen-covering drawer on return.
    val drawerState = remember { DrawerState(DrawerValue.Closed) }
    // Guarantee the drawer is closed whenever this screen is (re)entered.
    LaunchedEffect(Unit) { drawerState.close() }

    // Snackbar texts are pre-resolved here (composable scope) so the event lambdas below can use them.
    val snackMicNeeded = stringResource(R.string.snack_mic_needed)
    val snackVoiceSuffix = stringResource(R.string.snack_voice_type_suffix)
    val tplCameraFailed = stringResource(R.string.snack_camera_failed)
    val snackAttachAskOnly = stringResource(R.string.snack_attach_ask_only)
    val snackEnvFirst = stringResource(R.string.snack_env_first)

    val isAgentMode = mode == ChatMode.AGENT
    val isCodeMode = mode == ChatMode.CODE
    val canSend = when {
        isCodeMode -> input.isNotBlank()
        isAgentMode -> environment.canStart && !state.isBusy && input.isNotBlank()
        else -> !asking && (input.isNotBlank() || attachedImage != null || attachedFile != null)
    }

    // Voice-to-text into the input field. Prefer the bundled on-device speech model (Vosk) — the same
    // local model the assistant uses — and fall back to the system recognizer when no model is bundled.
    // The cloud speech-to-text path has been removed.
    //
    // A fresh Vosk engine is created per listen (and the previous one destroyed): VoskSpeechEngine.start
    // opens a new AudioRecord without releasing the old one, so reusing a single instance would leak the
    // microphone and make the second voice tap fail with "启动离线识别失败".
    val voskAvailable = remember { VoskSpeechEngine.isModelAvailable(context) }
    var vosk by remember { mutableStateOf<VoskSpeechEngine?>(null) }
    val speech = remember { SpeechController(context) }
    // Which engine the current listen is using, so we stop the right one. We prefer the phone's
    // built-in system recognizer (free — no API key, no billing — and more accurate) and only fall
    // back to the bundled offline Vosk model when no system recognizer will run.
    var usingVosk by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { vosk?.destroy(); speech.destroy() } }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) attachedImage = uri }

    // Take a photo with the system camera (no CAMERA permission needed — the camera app handles it).
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> if (success) attachedImage = pendingCameraUri else pendingCameraUri = null }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            attachedFile = uri
            attachedFileName = queryFileName(context, uri)
        }
    }

    val launchCamera: () -> Unit = {
        val photo = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo)
        pendingCameraUri = uri
        runCatching { cameraLauncher.launch(uri) }
            .onFailure { scope.launch { snackbarHostState.showSnackbar(tplCameraFailed.format(it.message ?: "")) } }
    }

    // Fallback path: the bundled fully-offline Vosk model (used only if no free system recognizer runs).
    val startVosk: () -> Unit = {
        usingVosk = true
        vosk?.destroy()
        val engine = VoskSpeechEngine(context).also { vosk = it }
        engine.start(object : VoskSpeechEngine.Callbacks {
            override fun onPartial(text: String) { input = joinVoice(voiceAnchor, text) }
            override fun onFinal(text: String) {
                if (text.isNotBlank()) input = joinVoice(voiceAnchor, text)
                listening = false
            }
            override fun onError(message: String) {
                listening = false
                scope.launch { snackbarHostState.showSnackbar("$message$snackVoiceSuffix") }
            }
        })
    }

    val startListening: () -> Unit = {
        listening = true
        // Anchor on the text already in the field so speech appends after it (keeps what you typed).
        voiceAnchor = input
        usingVosk = false
        if (speech.isAvailable) {
            // Prefer the phone's built-in recognizer: free (no key / no billing) and more accurate.
            speech.start(object : SpeechController.Callbacks {
                override fun onPartial(text: String) { input = joinVoice(voiceAnchor, text) }
                override fun onFinal(text: String) {
                    if (text.isNotBlank()) input = joinVoice(voiceAnchor, text)
                    listening = false
                }
                override fun onError(message: String) {
                    listening = false
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
                override fun onUnavailable(message: String) {
                    // No system recognizer could run — fall back to the free offline model if bundled.
                    if (voskAvailable) {
                        startVosk()
                    } else {
                        listening = false
                        scope.launch { snackbarHostState.showSnackbar("$message$snackVoiceSuffix") }
                    }
                }
            })
        } else if (voskAvailable) {
            startVosk()
        } else {
            listening = false
            scope.launch { snackbarHostState.showSnackbar("$snackMicNeeded$snackVoiceSuffix") }
        }
    }

    // Request RECORD_AUDIO in-context; start listening as soon as it's granted.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startListening()
        } else {
            scope.launch { snackbarHostState.showSnackbar(snackMicNeeded) }
        }
    }

    val toggleVoice: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        when {
            !granted -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            listening -> if (usingVosk) vosk?.stop() else speech.stop()
            else -> startListening()
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshEnvironment()
        codeViewModel.refreshStorage()
        onPauseOrDispose { }
    }

    codeNotice?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            codeViewModel.dismissNotice()
        }
    }

    // On the 代码 page, back peels one layer at a time — editor/preview, then the open project —
    // before it is allowed to leave the screen.
    BackHandler(enabled = isCodeMode && (codePane != CodePane.NONE || codeProject != null)) {
        when {
            codePane == CodePane.EDITOR -> codeViewModel.closeFile()
            codePane != CodePane.NONE -> codeViewModel.showPane(CodePane.NONE)
            else -> codeViewModel.closeProject()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only allow the swipe gesture while the drawer is open (to swipe it closed). When closed it
        // opens via the hamburger only, so the horizontal swipe is free for the 智能体/问答 pager.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ConeDrawer(
                modelLabel = label?.modelId,
                conversations = conversations,
                onDeleteConversation = { id -> viewModel.deleteConversation(id) },
                // Close the drawer *then* act in the SAME coroutine, so the close animation finishes before
                // we navigate / change state, leaving the drawer cleanly closed.
                onNewConversation = {
                    scope.launch {
                        drawerState.close()
                        if (isCodeMode) showCodeNewProject = true else viewModel.newConversation()
                    }
                },
                onOpenSettings = { scope.launch { drawerState.close(); onOpenSettings() } },
                onOpenProviders = { scope.launch { drawerState.close(); onOpenProviders() } },
                onOpenPermissions = { scope.launch { drawerState.close(); onOpenPermissions() } },
                onOpenRemote = { scope.launch { drawerState.close(); onOpenRemote() } },
                onOpenWatch = { scope.launch { drawerState.close(); onOpenWatch() } },
                onClearAllHistory = { scope.launch { drawerState.close(); viewModel.clearAllHistory() } },
                onOpenConversation = { id, convMode ->
                    scope.launch { drawerState.close(); viewModel.openConversation(id, convMode) }
                },
                incognitoAsk = incognitoAsk,
                showIncognito = mode == ChatMode.ASK,
                onToggleIncognito = { viewModel.setIncognitoAsk(it) },
            )
        },
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen bottom breathing glow: it sits behind the content, the floating input island AND
        // the nav bar, so the whole lower area is covered (no plain block left under the input). Shown on
        // the empty greeting and while 问答 is thinking; reflects the currently-active page.
        val showGlow = when (mode) {
            ChatMode.AGENT -> agentMessages.isEmpty() && !state.isBusy
            ChatMode.ASK -> askMessages.isEmpty() || asking
            // 写代码: behind the suggestions, but not behind an open project's transcript.
            ChatMode.CODE -> codeProject == null
        }
        if (showGlow) BreathingGlow()
    Scaffold(
        // Transparent so the glow + single app background show continuously behind the content, the
        // floating input island and the immersive nav bar (no Scaffold-drawn slab).
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
          Column {
            TopAppBar(
                // 模式岛放回顶栏右侧；顶栏透明，页面底色与呼吸光晕保持连续。
                actions = {
                    ModeIsland(mode = mode, onChange = viewModel::setMode)
                    Spacer(Modifier.size(6.dp))
                },
                // 透明顶栏：让页面底色与呼吸光晕连续，而不是被一条 surface 色块切断。
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.cd_menu))
                    }
                },
                // Center-left model id — tap to switch model inline (works even mid-answer).
                title = {
                    ModelSwitcher(
                        modelId = label?.modelId,
                        models = allModels,
                        activeProviderId = activeModel?.providerId,
                        activeModelId = activeModel?.modelId,
                        onSelect = { pid, mid -> viewModel.switchModel(pid, mid) },
                        onOpenSettings = onOpenSettings,
                    )
                },
            )
          }
        },
        bottomBar = {
            InputBar(
                value = input,
                onValueChange = { edited ->
                    // A manual edit while the mic is on re-anchors voice output so it appends AFTER
                    // your edit, and resets the recognizer's running transcript so it won't re-emit
                    // the words you just changed — i.e. you can edit the text with the mic still on.
                    if (listening && edited != input) {
                        voiceAnchor = edited
                        if (usingVosk) vosk?.resetTranscript()
                    }
                    input = edited
                },
                enabled = canSend,
                listening = listening,
                attachedImage = attachedImage,
                imageProgress = imageProgress,
                attachedFileName = attachedFileName,
                attachmentsEnabled = !isAgentMode && !isCodeMode,
                webSearchVisible = false,
                codeMode = isCodeMode,
                onNewProject = { showCodeNewProject = true },
                onOpenHistory = { showCodeHistory = true },
                webSearchEnabled = webSearchEnabled,
                onToggleWebSearch = { viewModel.toggleWebSearch() },
                placeholder = when {
                    // The agent stopped on a question, so the field is for the answer.
                    isCodeMode && codeAwaitingAnswer -> stringResource(R.string.code_hint_answer)
                    isCodeMode && codeProject != null -> stringResource(R.string.code_hint)
                    isCodeMode -> stringResource(R.string.code_hint_home)
                    isAgentMode -> stringResource(R.string.hint_agent)
                    else -> stringResource(R.string.hint_ask)
                },
                thinking = if (isCodeMode) codeRunning else !isAgentMode && asking,
                onStop = { if (isCodeMode) codeViewModel.stop() else viewModel.cancelAsk() },
                onSend = {
                    if (isCodeMode) {
                        codeViewModel.submit(input)
                        input = ""
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    } else if (isAgentMode && !environment.canStart) {
                        scope.launch {
                            snackbarHostState.showSnackbar(snackEnvFirst)
                        }
                    } else {
                        viewModel.send(
                            input,
                            if (isAgentMode) null else attachedImage,
                            if (isAgentMode) null else attachedFile,
                        )
                        input = ""
                        attachedImage = null
                        attachedFile = null
                        attachedFileName = null
                        // Question's on its way — retract the keyboard and clear input focus.
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        // Agent tasks run in the background behind a floating monitor window, so the
                        // app itself steps out of the way instead of staying on screen.
                        if (isAgentMode) {
                            scope.launch {
                                kotlinx.coroutines.delay(350)
                                (context as? android.app.Activity)?.moveTaskToBack(true)
                            }
                        }
                    }
                },
                onVoice = toggleVoice,
                onCamera = launchCamera,
                onGallery = { imagePicker.launch("image/*") },
                onFile = { filePicker.launch("*/*") },
                onAttachDisabled = {
                    scope.launch { snackbarHostState.showSnackbar(snackAttachAskOnly) }
                },
                onRemoveImage = { attachedImage = null },
                onRemoveFile = { attachedFile = null; attachedFileName = null },
            )
        },
    ) { padding ->
        // Two swipeable pages — 智能体 (page 0) and 问答 (page 1) — kept in sync with the toggle.
        val pagerState = rememberPagerState(initialPage = mode.page) { 3 }
        // Follow targetPage, NOT currentPage. With three pages, an animated jump from 智能体 to 代码
        // sweeps *through* 问答, and currentPage ticks over as it passes the midpoint — which used to
        // fire this sync, set the mode to 问答, and hijack the very animation that was in flight, so
        // the tap landed one page short of where it was aimed. targetPage is the destination from the
        // first frame, and for a user swipe it commits as soon as the direction is decided, so the
        // island still lights up immediately.
        androidx.compose.runtime.LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.targetPage }.collect { page ->
                val target = modeOfPage(page)
                if (viewModel.mode.value != target) viewModel.setMode(target)
            }
        }
        androidx.compose.runtime.LaunchedEffect(mode) {
            if (pagerState.currentPage != mode.page) pagerState.animateScrollToPage(mode.page)
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            if (page == 2) {
                CodePage(
                    viewModel = codeViewModel,
                    onFillInput = { input = it },
                    onPickFolder = { showCodeFolder = true },
                    onOpenWorkflowMemory = { showCodeHabits = true },
                    modifier = Modifier.fillMaxSize(),
                )
                return@HorizontalPager
            }
            ChatModePage(
                isAgentMode = page == 0,
                messages = if (page == 0) agentMessages else askMessages,
                state = state,
                environment = environment,
                asking = asking,
                searching = searching,
                onOpenPermissions = onOpenPermissions,
                onOpenSettings = onOpenSettings,
                onPause = { viewModel.pause() },
                onResume = { viewModel.resume() },
                onStop = { viewModel.stop() },
                onConfirm = { viewModel.confirm(it) },
                onConfirmPlan = { viewModel.confirmPlan(it) },
                cumulativeTokens = cumulativeTokens,
                incognitoAsk = incognitoAsk,
                streamingText = if (page == 1) streamingText else null,
                onFillInput = { input = it },
            )
        }
    }
    }

    // Hosted here rather than inside the page: the editor and the preview take over the whole
    // display, and inside the pager they would be boxed into one page's bounds.
    if (isCodeMode) CodeOverlays(codeViewModel)

    if (showCodeHistory) {
        CodeHistorySheet(
            viewModel = codeViewModel,
            onDismiss = { showCodeHistory = false },
            onOpen = { project ->
                showCodeHistory = false
                viewModel.setMode(ChatMode.CODE)
                codeViewModel.openProject(project)
            },
        )
    }
    if (showCodeNewProject) {
        CodeNewProjectDialog(
            onDismiss = { showCodeNewProject = false },
            onCreate = { name, template ->
                showCodeNewProject = false
                viewModel.setMode(ChatMode.CODE)
                codeViewModel.createProject(name, template)
            },
        )
    }
    if (showCodeHabits) {
        CodeWorkflowMemorySheet(viewModel = codeViewModel, onDismiss = { showCodeHabits = false })
    }
    if (showCodeFolder) {
        CodeFolderPickerDialog(viewModel = codeViewModel, onDismiss = { showCodeFolder = false })
    }
    if (codeHelpVisible) {
        CodeHelpDialog(onDismiss = { codeViewModel.dismissHelp() })
    }
    }
}

/** Page index of each mode, and back again — the pager and the island stay in step through these. */
private val ChatMode.page: Int
    get() = when (this) {
        ChatMode.AGENT -> 0
        ChatMode.ASK -> 1
        ChatMode.CODE -> 2
    }

private fun modeOfPage(page: Int): ChatMode = when (page) {
    0 -> ChatMode.AGENT
    1 -> ChatMode.ASK
    else -> ChatMode.CODE
}

/**
 * Appends freshly recognized speech [spoken] after the [anchor] text already in the field. A single
 * space is inserted only between two Latin words; Chinese (CJK) joins with no gap, and an anchor that
 * already ends in whitespace is left as-is.
 */
private fun joinVoice(anchor: String, spoken: String): String {
    if (spoken.isBlank()) return anchor
    if (anchor.isEmpty()) return spoken
    val a = anchor.last()
    val b = spoken.first()
    val needsSpace = !a.isWhitespace() && !b.isWhitespace() && !a.isCjk() && !b.isCjk()
    return if (needsSpace) "$anchor $spoken" else anchor + spoken
}

private fun Char.isCjk(): Boolean = this in '㐀'..'鿿'

/** One page of the swipeable chat: mode-specific banners/status above the shared conversation. */
@Composable
private fun ChatModePage(
    isAgentMode: Boolean,
    messages: List<UiMessage>,
    state: com.cone.agent.agent.AgentRuntimeState,
    environment: EnvironmentStatus,
    asking: Boolean,
    searching: Boolean,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onConfirmPlan: (Boolean) -> Unit,
    cumulativeTokens: Int,
    incognitoAsk: Boolean,
    streamingText: String?,
    /** Tap on a starter suggestion: fills the page's shared input (never sends). */
    onFillInput: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    // Follow the streaming answer as it grows so the newest text stays in view (ChatGPT-style).
    androidx.compose.runtime.LaunchedEffect(streamingText) {
        if (streamingText != null) runCatching { listState.scrollToItem(messages.size) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // 提示性 banner：列出还没开启的能力，但不再拦截发送——接口直达任务无需屏幕能力也能执行。
        if (isAgentMode && !environment.fullyReady) {
            EnvironmentBanner(environment = environment, onFix = onOpenPermissions)
        }
        if (!isAgentMode && !environment.modelReady) {
            ModelHintCard(onOpenSettings = onOpenSettings)
        }
        // The status card stays up while the task runs, then lingers briefly on a terminal result
        // (succeeded/failed) so the user can read the outcome, and auto-closes afterwards. Without
        // this the card kept showing forever, since the runtime status stays SUCCEEDED/FAILED until
        // the next task starts.
        val terminal = state.status == TaskStatus.SUCCEEDED || state.status == TaskStatus.FAILED
        var terminalCardDismissed by remember { mutableStateOf(false) }
        LaunchedEffect(state.status) {
            if (terminal) {
                terminalCardDismissed = false
                kotlinx.coroutines.delay(Constants.AGENT_CARD_AUTO_DISMISS_MS)
                terminalCardDismissed = true
            } else {
                terminalCardDismissed = false
            }
        }
        if (isAgentMode && (state.isBusy || (terminal && !terminalCardDismissed))) {
            AgentStatusCard(state = state, onPause = onPause, onResume = onResume, onStop = onStop)
        }
        if (isAgentMode) {
            val ctx = LocalContext.current
            state.pending?.let { pending ->
                ConfirmCard(
                    reason = pending.reason,
                    actionDescription = pending.action.describe(ctx),
                    onApprove = { onConfirm(true) },
                    onReject = { onConfirm(false) },
                )
            }
            // 计划模式：总体计划先呈现给用户，确认后才开始执行任何动作。
            state.pendingPlan?.let { steps ->
                PlanConfirmCard(
                    steps = steps,
                    onApprove = { onConfirmPlan(true) },
                    onReject = { onConfirmPlan(false) },
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Show the greeting whenever this page's own thread is empty. Only the agent page also
            // waits for the agent to be idle — a running agent task must not blank the 问答 greeting.
            // The breathing glow is drawn once behind the whole screen (see ChatScreen); here we only
            // decide greeting vs conversation, and show the live "thinking" timer.
            val showGreeting = messages.isEmpty() && (!isAgentMode || !state.isBusy)
            val thinking = !isAgentMode && asking
            if (showGreeting) {
                // 空态只留问候语与一句说明，不再堆三条建议卡。
                GreetingView(
                    isAgent = isAgentMode,
                    incognito = !isAgentMode && incognitoAsk,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(messages, key = { it.id }) { MessageBubble(it) }
                    // The answer streaming in, rendered as a live assistant bubble until it's saved.
                    if (!streamingText.isNullOrEmpty()) {
                        item(key = "streaming") { StreamingBubble(streamingText) }
                    }
                }
            }
            // Live elapsed timer while thinking — only until the first token arrives; once the answer
            // starts streaming, the growing bubble itself is the feedback.
            if (thinking && streamingText.isNullOrEmpty()) {
                ThinkingIndicator(searching = searching, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        // Cumulative token readout, pinned just under the conversation (above the input bar).
        // ChatModePage 里的 !isAgentMode 就是问答页，无痕标记只属于它。
        ContextUsageBar(tokens = cumulativeTokens, incognito = !isAgentMode && incognitoAsk)
    }
}

/** The assistant answer as it streams in — same layout as a saved reply, minus the footer/actions. */
@Composable
private fun StreamingBubble(text: String) {
    com.cone.agent.ui.components.MarkdownText(
        markdown = text,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    )
}

/** Tiny footer under the conversation showing the current conversation's cumulative token usage. */
@Composable
private fun ContextUsageBar(tokens: Int, incognito: Boolean) {
    if (tokens <= 0 && !incognito) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (incognito) {
            Icon(
                Icons.Outlined.VisibilityOff,
                contentDescription = stringResource(R.string.cd_incognito),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
        }
        Text(
            text = stringResource(R.string.context_usage, tokens),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A small pill above the input: shows "联网搜索中…" while the grounding step runs, otherwise a live
 * "thinking …Ns" timer (Claude Code style). Visible only while a 问答 request is in flight.
 */
@Composable
private fun ThinkingIndicator(searching: Boolean, modifier: Modifier = Modifier) {
    var elapsedMs by remember { mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - start
            kotlinx.coroutines.delay(100)
        }
    }
    Surface(
        modifier = modifier.padding(bottom = 16.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = if (searching) {
                stringResource(R.string.web_searching)
            } else {
                stringResource(R.string.thinking_elapsed, "%.1fs".format(elapsedMs / 1000.0))
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A soft blue gradient anchored to the bottom of the screen whose intensity gently pulses, like a
 * breathing light — the Gemini-style ambient glow behind the input area.
 */
@Composable
private fun BoxScope.BreathingGlow() {
    val transition = rememberInfiniteTransition(label = "glow")
    val alpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(240.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, primary.copy(alpha = alpha)),
                ),
            ),
    )
}

/** Centered empty state for a fresh thread: time-of-day greeting, one line saying what this mode is
 *  good for, and tappable starter suggestions.
 *
 *  Deliberately the same visual language as the code page's empty state: theme-colored icon tile,
 *  large title, muted subtitle, pill suggestions. Everything is drawn from [MaterialTheme] so the
 *  first screen follows light/dark like the rest of the app instead of hardcoding a peach tile.
 *  A suggestion only fills the shared input field (never auto-sends), so the user still decides.
 *  In an incognito 问答 it shows the "无痕模式" line and no starters. */
@Composable
private fun GreetingView(
    isAgent: Boolean,
    incognito: Boolean = false,
) {
    val greetingRes = remember(incognito) {
        if (incognito) {
            R.string.greeting_incognito
        } else {
            when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 0..4 -> R.string.greeting_dawn
                in 5..10 -> R.string.greeting_morning
                in 11..12 -> R.string.greeting_noon
                in 13..17 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            }
        }
    }
    val greeting = stringResource(greetingRes)
    val subtitleRes = when {
        incognito -> R.string.chat_subtitle_incognito
        isAgent -> R.string.chat_subtitle_agent
        else -> R.string.chat_subtitle_ask
    }

    // Gemini 的左对齐大标题 + Muse 的纸质留白：标题不用渐变，靠字号与留白建立层级。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = greeting,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(subtitleRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The navigation drawer (sidebar).
 *
 * Layout, top to bottom: identity row (app mark, model label, incognito), one primary action
 * (发起新对话), the history list, then a compact strip of the five destinations that used to be
 * full-width menu rows — those ate almost the whole drawer and left room for only two conversations.
 */
@Composable
private fun ConeDrawer(
    modelLabel: String?,
    conversations: List<ConversationSummary>,
    onDeleteConversation: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenWatch: () -> Unit,
    onClearAllHistory: () -> Unit,
    onOpenConversation: (Long, ConversationMode) -> Unit,
    incognitoAsk: Boolean = false,
    showIncognito: Boolean = false,
    onToggleIncognito: (Boolean) -> Unit = {},
) {
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            DrawerHeader(
                modelLabel = modelLabel,
                incognitoAsk = incognitoAsk,
                showIncognito = showIncognito,
                onToggleIncognito = onToggleIncognito,
            )

            FilledTonalButton(
                onClick = onNewConversation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.drawer_new_conversation), style = MaterialTheme.typography.labelLarge)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.drawer_history),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (conversations.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        conversations.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { showClearAll = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(
                            stringResource(R.string.drawer_clear_short),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (conversations.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.drawer_no_history),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        items(conversations, key = { it.id }) { item ->
                            DrawerHistoryRow(
                                item = item,
                                onOpen = { onOpenConversation(item.id, item.mode) },
                                onDelete = { pendingDelete = item },
                            )
                        }
                    }
                }
            }

            DrawerFooterNav(
                onOpenSettings = onOpenSettings,
                onOpenProviders = onOpenProviders,
                onOpenPermissions = onOpenPermissions,
                onOpenRemote = onOpenRemote,
                onOpenWatch = onOpenWatch,
            )
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_conv_title)) },
            text = { Text(stringResource(R.string.delete_conv_msg)) },
            confirmButton = {
                TextButton(onClick = { onDeleteConversation(target.id); pendingDelete = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.clear_all_title)) },
            text = { Text(stringResource(R.string.clear_all_msg)) },
            confirmButton = {
                TextButton(
                    onClick = { showClearAll = false; onClearAllHistory() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.action_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Compact identity row: app mark, name, current model, incognito toggle (ASK mode only). */
@Composable
private fun DrawerHeader(
    modelLabel: String?,
    incognitoAsk: Boolean,
    showIncognito: Boolean,
    onToggleIncognito: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 18.dp, end = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "ConeAI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            modelLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 无痕只影响问答模式（智能体会话总要落盘），其它模式不出现这个入口。
        if (showIncognito) {
            IconButton(
                onClick = { onToggleIncognito(!incognitoAsk) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(R.string.cd_incognito_toggle),
                    modifier = Modifier.size(19.dp),
                    tint = if (incognitoAsk) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** Five destinations as icon cells, sized so the history list keeps the drawer's height. */
@Composable
private fun DrawerFooterNav(
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenWatch: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        DrawerNavCell(Icons.Outlined.Settings, R.string.drawer_short_settings, onOpenSettings)
        DrawerNavCell(Icons.Outlined.Dns, R.string.drawer_short_providers, onOpenProviders)
        DrawerNavCell(Icons.Outlined.Security, R.string.drawer_short_permissions, onOpenPermissions)
        DrawerNavCell(Icons.Outlined.PhonelinkRing, R.string.drawer_short_remote, onOpenRemote)
        DrawerNavCell(Icons.Outlined.Watch, R.string.drawer_short_watch, onOpenWatch)
    }
}

@Composable
private fun RowScope.DrawerNavCell(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    // 官方 Surface 负责水波纹、点击语义与形状裁剪，不必再手写 clickable + clip。
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 一条历史会话：官方 ListItem 承载（前导模式图标、标题、相对时间、尾部删除）。 */
@Composable
private fun DrawerHistoryRow(
    item: ConversationSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val isAgent = item.mode == ConversationMode.AGENT
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen() },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                if (isAgent) Icons.Outlined.AutoAwesome else Icons.Outlined.ChatBubbleOutline,
                contentDescription = stringResource(if (isAgent) R.string.mode_agent else R.string.mode_ask),
                tint = if (isAgent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(18.dp),
            )
        },
        headlineContent = {
            Text(
                item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                DateUtils.getRelativeTimeSpanString(
                    item.updatedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.cd_delete),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    )
}

@Composable
private fun AgentStatusCard(
    state: com.cone.agent.agent.AgentRuntimeState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    var showPlan by remember { mutableStateOf(false) }
    // 人类观看用的计时：任务一开始就记，暂停时停表（Mika 那种"正在做事"的实时感）。
    var startedAt by remember(state.instruction) { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.status) {
        if (state.status == TaskStatus.RUNNING && startedAt == 0L) {
            startedAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(state.isBusy) {
        while (state.isBusy) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedSec = if (startedAt == 0L) 0 else ((now - startedAt) / 1000).toInt()
    val finished = state.status == TaskStatus.SUCCEEDED ||
        state.status == TaskStatus.FAILED ||
        state.status == TaskStatus.CANCELLED
    val pulse = rememberInfiniteTransition(label = "agent-live")
    val dotAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "agent-dot",
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 状态行：脉动圆点 + 人话状态 + 用时
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                state.status == TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                                finished -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha)
                            },
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusLabel(state.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (startedAt != 0L) {
                    Text(
                        "⏱ " + formatElapsed(elapsedSec),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.instruction.ifBlank { stringResource(R.string.task_default) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 当前动作：人类能看懂的一行（去掉工具名噪音）
            if (!finished && state.currentAction.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            humanAction(state.currentAction),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.maxSteps >= Constants.UNLIMITED_MAX_STEPS) {
                        stringResource(R.string.agent_step_short, state.displayStep)
                    } else {
                        stringResource(R.string.agent_step_of, state.displayStep, state.maxSteps)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!finished) {
                LinearProgressIndicator(
                    progress = { (state.stepIndex.toFloat() / state.maxSteps.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            if (state.summary.isNotBlank() && finished) {
                Text(
                    state.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (showPlan && state.plan.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Text(
                        text = state.plan.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (!finished) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.plan.isNotEmpty()) {
                        TextButton(onClick = { showPlan = !showPlan }) {
                            Text(stringResource(R.string.agent_view_plan), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    TextButton(onClick = if (state.isPaused) onResume else onPause) {
                        Text(
                            stringResource(if (state.isPaused) R.string.fc_resume else R.string.fc_pause),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = onStop,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(stringResource(R.string.fc_stop), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** 秒数 → 1m23s / 47s，手机上比纯秒数好读。 */
private fun formatElapsed(seconds: Int): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m${seconds % 60}s"



/** 计划模式的确认卡片：列出总体计划，等用户点「开始执行」或「取消任务」。 */
@Composable
private fun PlanConfirmCard(
    steps: List<String>,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.plan_confirm_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onApprove) { Text(stringResource(R.string.plan_confirm_start)) }
                OutlinedButton(onClick = onReject) { Text(stringResource(R.string.plan_confirm_cancel)) }
            }
        }
    }
}

@Composable
private fun ConfirmCard(
    reason: String,
    actionDescription: String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.confirm_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(R.string.confirm_about_to, actionDescription),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onApprove) { Text(stringResource(R.string.confirm_approve)) }
                OutlinedButton(onClick = onReject) { Text(stringResource(R.string.confirm_reject)) }
            }
        }
    }
}

@Composable
private fun EnvironmentBanner(environment: EnvironmentStatus, onFix: () -> Unit) {
    val missing = buildList {
        if (!environment.modelReady) add(stringResource(R.string.env_pick_model))
        if (!environment.accessibilityReady) {
            add(
                if (environment.accessibilityRestarting) {
                    stringResource(R.string.env_accessibility_restarting)
                } else {
                    stringResource(R.string.env_accessibility)
                },
            )
        }
        if (!environment.overlayReady) add(stringResource(R.string.env_overlay))
        if (!environment.captureReady) add(stringResource(R.string.env_capture))
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.env_need), style = MaterialTheme.typography.titleMedium)
                Text(
                    missing.joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Button(onClick = onFix) { Text(stringResource(R.string.env_fix)) }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    listening: Boolean,
    attachedImage: Uri?,
    imageProgress: Float?,
    attachedFileName: String?,
    attachmentsEnabled: Boolean,
    webSearchVisible: Boolean,
    webSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    codeMode: Boolean,
    onNewProject: () -> Unit,
    onOpenHistory: () -> Unit,
    placeholder: String,
    thinking: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onAttachDisabled: () -> Unit,
    onRemoveImage: () -> Unit,
    onRemoveFile: () -> Unit,
) {
    var attachMenuOpen by remember { mutableStateOf(false) }
    // No background slab — the bottom bar is fully transparent so only the rounded pill below floats as
    // an "island" over the continuous page background (and the transparent, immersive nav bar). The
    // inner content is inset above the nav bar / keyboard (union = whichever is taller).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            attachedImage?.let { uri ->
                Box(modifier = Modifier.padding(bottom = 8.dp)) {
                    AsyncImage(
                        model = uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
                    )
                    // While the picture is being prepared, the thumbnail dims behind a determinate
                    // ring with the percentage inside it — a large photo takes long enough that a
                    // still, unexplained thumbnail reads as the app having ignored the tap.
                    imageProgress?.let { progress ->
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x99000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                progress = { progress },
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color(0x33FFFFFF),
                                strokeCap = StrokeCap.Round,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier.align(Alignment.TopEnd).size(24.dp),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove_image))
                    }
                }
            }
            attachedFileName?.let { name ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp).widthIn(max = 200.dp),
                        )
                        IconButton(onClick = onRemoveFile, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove_file))
                        }
                    }
                }
            }
            // Floating "island" input pill: + / text / mic / send, with a drop shadow so it reads as
            // hovering over the page (no surrounding bar). Shared with 写代码 so the two screens
            // cannot drift apart.
            ConeInputPill(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                sendEnabled = enabled,
                busy = thinking,
                onSend = onSend,
                onStop = onStop,
                // 写代码 cancels its agent outright rather than pausing a thought, so it says Stop.
                busyIcon = if (codeMode) Icons.Filled.Stop else Icons.Filled.Pause,
                leading = {
                    if (codeMode) {
                        IconButton(onClick = onNewProject) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.code_new_project))
                        }
                    } else Box {
                        IconButton(
                            onClick = { if (attachmentsEnabled) attachMenuOpen = true else onAttachDisabled() },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_attachment))
                        }
                        DropdownMenu(
                            expanded = attachMenuOpen,
                            onDismissRequest = { attachMenuOpen = false },
                            modifier = Modifier.clip(ConeShapes.Menu),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_photo)) },
                                leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                                onClick = { attachMenuOpen = false; onCamera() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_gallery)) },
                                leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                onClick = { attachMenuOpen = false; onGallery() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_file)) },
                                leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                                onClick = { attachMenuOpen = false; onFile() },
                            )
                        }
                    }
                },
                trailing = {
                    // Where 问答 keeps 联网搜索, 写代码 keeps 历史项目 — the thing you reach for on a
                    // coding screen is the project you had open yesterday.
                    if (codeMode) {
                        IconButton(onClick = onOpenHistory) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = stringResource(R.string.code_history),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (webSearchVisible) {
                        IconButton(onClick = onToggleWebSearch) {
                            Icon(
                                Icons.Filled.TravelExplore,
                                contentDescription = stringResource(R.string.cd_web_search),
                                tint = if (webSearchEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    IconButton(onClick = onVoice) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.cd_voice),
                            tint = if (listening) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        }
    }
}


/**
 * The top-right island: 智能体 / 问答 / 代码 — three pages of one pager, not three destinations.
 *
 * Only the active segment carries its label; the other two collapse to their glyph, which is what
 * lets a third page fit up here without crowding the model switcher.
 *
 * The width is animated in exactly one place. An earlier version also wrapped the row in
 * `animateContentSize()`, so the label's own expand animation and the container's size animation
 * fought over the same dimension and the pill rubber-banded. The label is also pinned to a single
 * non-wrapping line: while it expands it is briefly given a very narrow width, and a wrappable
 * label answers that by laying out on two lines — which made the whole island jump taller and snap
 * back on every switch.
 */
@Composable
private fun ModeIsland(mode: ChatMode, onChange: (ChatMode) -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(modifier = Modifier.padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            IslandSegment(
                icon = Icons.Filled.AutoAwesome,
                label = stringResource(R.string.mode_agent),
                selected = mode == ChatMode.AGENT,
            ) { onChange(ChatMode.AGENT) }
            IslandSegment(
                icon = Icons.Filled.Forum,
                label = stringResource(R.string.mode_ask),
                selected = mode == ChatMode.ASK,
            ) { onChange(ChatMode.ASK) }
            IslandSegment(
                icon = Icons.Filled.Code,
                label = stringResource(R.string.mode_code),
                selected = mode == ChatMode.CODE,
            ) { onChange(ChatMode.CODE) }
        }
    }
}

@Composable
private fun IslandSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(ISLAND_MS, easing = FastOutSlowInEasing),
        label = "islandBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(ISLAND_MS, easing = FastOutSlowInEasing),
        label = "islandFg",
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(17.dp))
        AnimatedVisibility(
            visible = selected,
            // The label fades in slightly behind the widening pill and out ahead of it closing, so
            // text never sits on a container that has not made room for it yet.
            enter = expandHorizontally(tween(ISLAND_MS, easing = FastOutSlowInEasing)) +
                fadeIn(tween(ISLAND_MS - 80, delayMillis = 80)),
            exit = shrinkHorizontally(tween(ISLAND_MS, easing = FastOutSlowInEasing)) +
                fadeOut(tween(100)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(5.dp))
                Text(
                    label,
                    color = fg,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

private const val ISLAND_MS = 240


@Composable
private fun ModelHintCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.model_hint_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.model_hint_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.model_hint_action)) }
        }
    }
}

/** Best-effort display name for a picked file content Uri. */
private fun queryFileName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: context.getString(R.string.file_default)

@Composable
private fun statusLabel(status: TaskStatus): String = stringResource(
    when (status) {
        TaskStatus.IDLE -> R.string.status_idle
        TaskStatus.RUNNING -> R.string.status_running
        TaskStatus.OBSERVING -> R.string.status_observing
        TaskStatus.PAUSED -> R.string.status_paused
        TaskStatus.WAITING_CONFIRM -> R.string.status_waiting
        TaskStatus.SUCCEEDED -> R.string.status_succeeded
        TaskStatus.FAILED -> R.string.status_failed
        TaskStatus.CANCELLED -> R.string.status_cancelled
    },
)
