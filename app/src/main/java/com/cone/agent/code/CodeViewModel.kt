package com.cone.agent.code

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.local.entity.VisionModelView
import com.cone.agent.data.repository.ProviderRepository
import com.cone.agent.data.repository.SelectedAgentModel
import com.cone.agent.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Where projects are being written, and whether the user still has to grant access for it. */
data class CodeStorageState(
    val root: File,
    val hasAccess: Boolean,
    val fallback: Boolean,
)

/** The model's project suggestions on the home screen. */
data class IdeasState(
    val loading: Boolean = false,
    val items: List<ProjectIdea> = emptyList(),
    val error: String? = null,
)

/** Which sheet/overlay the workspace is showing on top of the conversation. */
enum class CodePane { NONE, FILES, EDITOR, PREVIEW }

@HiltViewModel
class CodeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: CodeWorkspace,
    private val agent: CodeAgent,
    private val ideas: CodeIdeas,
    private val storage: CodeStorage,
    private val codeMemory: CodeMemoryRepository,
    private val settingsRepository: SettingsRepository,
    providerRepository: ProviderRepository,
) : ViewModel() {

    val projects: StateFlow<List<CodeProject>> = workspace.projects

    private val _current = MutableStateFlow<CodeProject?>(null)
    val current: StateFlow<CodeProject?> = _current.asStateFlow()

    private val _tree = MutableStateFlow<List<CodeNode>>(emptyList())
    val tree: StateFlow<List<CodeNode>> = _tree.asStateFlow()

    private val _open = MutableStateFlow<OpenFile?>(null)
    val open: StateFlow<OpenFile?> = _open.asStateFlow()

    private val _pane = MutableStateFlow(CodePane.NONE)
    val pane: StateFlow<CodePane> = _pane.asStateFlow()

    private val _turns = MutableStateFlow<List<CodeTurn>>(emptyList())
    val turns: StateFlow<List<CodeTurn>> = _turns.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** One-shot user-facing notice (save failed, file too large…), cleared by the UI once shown. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _previewFile = MutableStateFlow<File?>(null)
    val previewFile: StateFlow<File?> = _previewFile.asStateFlow()

    /** True when the open project has something a WebView can render, gating the preview action. */
    private val _previewable = MutableStateFlow(false)
    val previewable: StateFlow<Boolean> = _previewable.asStateFlow()

    /** Bumped on every write to the project; the open preview watches it and reloads. */
    private val _previewVersion = MutableStateFlow(0)
    val previewVersion: StateFlow<Int> = _previewVersion.asStateFlow()

    val allModels: StateFlow<List<VisionModelView>> = providerRepository.allModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The model this screen will use. It follows 问答's model with the agent's as fallback — the same
     * resolution [CodeAgent] performs — so the chip in the header can never disagree with what runs.
     */
    val activeModel: StateFlow<SelectedAgentModel?> = combine(
        settingsRepository.chatModel,
        settingsRepository.selectedModel,
    ) { chat, agentModel -> chat ?: agentModel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val modelId: StateFlow<String?> = activeModel
        .map { it?.modelId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _showHelp = MutableStateFlow(false)
    val showHelp: StateFlow<Boolean> = _showHelp.asStateFlow()

    private val _ideas = MutableStateFlow(IdeasState())
    val ideasState: StateFlow<IdeasState> = _ideas.asStateFlow()

    /** 工作流记忆：the user's standing habits, applied to every project. */
    val workflowMemories: StateFlow<List<WorkflowMemory>> = codeMemory.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val workflowEnabled: StateFlow<Boolean> = codeMemory.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** 计划模式：the agent plans first and waits for approval before touching any file. */
    val planMode: StateFlow<Boolean> = settingsRepository.codePlanMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** A plan is on screen waiting for 开始执行 / 取消. */
    private val _awaitingPlan = MutableStateFlow(false)
    val awaitingPlan: StateFlow<Boolean> = _awaitingPlan.asStateFlow()

    /** The agent asked something and stopped; the next message the user sends is the answer. */
    private val _awaitingAnswer = MutableStateFlow(false)
    val awaitingAnswer: StateFlow<Boolean> = _awaitingAnswer.asStateFlow()

    private val storageTick = MutableStateFlow(0)

    val storageState: StateFlow<CodeStorageState> = combine(storage.root, storageTick) { root, _ ->
        CodeStorageState(root = root, hasAccess = storage.hasAccess(), fallback = storage.isFallback())
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        CodeStorageState(storage.root.value, storage.hasAccess(), storage.isFallback()),
    )

    private var job: Job? = null

    init {
        viewModelScope.launch { workspace.refresh() }
    }

    /* ---------------- storage ---------------- */

    /** Re-reads the permission after a trip to system settings, and re-lists what is on disk. */
    fun refreshStorage() {
        storage.refresh()
        storageTick.value += 1
        viewModelScope.launch { workspace.refresh() }
    }

    fun accessSettingsIntents() = storage.accessSettingsIntents()

    fun browseStart(): File = storage.browseStart()

    fun subDirectories(dir: File): List<File> = storage.subDirectories(dir)

    fun defaultRoot(): File = storage.defaultRoot()

    /** Moves the workspace to [dir] (null restores the default). Existing projects stay where they are. */
    fun setRoot(dir: File?) {
        if (dir != null && !storage.isUsable(dir)) {
            _notice.value = LocaleHelper.string(context, R.string.code_err_folder, dir.absolutePath)
            return
        }
        storage.setRoot(dir)
        refreshStorage()
    }

    fun createFolder(parent: File, name: String): File? {
        val clean = name.trim().ifBlank { return null }
        val dir = File(parent, clean)
        if (!dir.mkdirs() && !dir.isDirectory) {
            _notice.value = LocaleHelper.string(context, R.string.code_err_folder, dir.absolutePath)
            return null
        }
        return dir
    }

    /* ---------------- plan mode ---------------- */

    fun setPlanMode(value: Boolean) {
        viewModelScope.launch { settingsRepository.setCodePlanMode(value) }
    }

    /** Approves the plan on screen and runs it — this run ignores plan mode, having just passed it. */
    fun approvePlan() {
        if (!_awaitingPlan.value) return
        _awaitingPlan.value = false
        send(LocaleHelper.string(context, R.string.code_plan_go), planOverride = false)
    }

    fun cancelPlan() {
        if (!_awaitingPlan.value) return
        _awaitingPlan.value = false
        _turns.update { it + CodeTurn(CodeRole.NOTE, LocaleHelper.string(context, R.string.code_plan_cancelled)) }
        persist()
    }

    /* ---------------- the two memories ---------------- */

    fun setWorkflowEnabled(value: Boolean) {
        viewModelScope.launch { codeMemory.setEnabled(value) }
    }

    fun deleteWorkflowMemory(id: Long) {
        viewModelScope.launch { codeMemory.remove(id) }
    }

    fun clearWorkflowMemories() {
        viewModelScope.launch { codeMemory.clear() }
    }

    /**
     * Opens the project's own memory — its CONE.md — in the editor, creating a starter file when the
     * project has none. Written as a real file rather than hidden app state on purpose: the user can
     * read it, edit it, and it travels with the folder when they copy the project to a computer.
     */
    fun openInstructions() {
        val project = _current.value ?: return
        val name = workspace.instructionsName()
        viewModelScope.launch {
            if (!workspace.exists(project, name)) {
                workspace.write(project, name, LocaleHelper.string(context, R.string.code_notes_template, project.name))
                    .onFailure {
                        _notice.value = it.message
                        return@launch
                    }
                refreshTree()
            }
            openFile(name)
        }
    }

    /* ---------------- suggestions ---------------- */

    /** Fetches project ideas; a cached set is kept until [force] asks for fresh ones. */
    fun loadIdeas(force: Boolean = false) {
        if (_ideas.value.loading) return
        if (!force && _ideas.value.items.isNotEmpty()) return
        _ideas.value = IdeasState(loading = true)
        viewModelScope.launch {
            val existing = workspace.projects.value.map { it.name }
            ideas.suggest(existing).fold(
                onSuccess = { _ideas.value = IdeasState(items = it) },
                onFailure = { _ideas.value = IdeasState(error = it.message) },
            )
        }
    }

    /** Builds one of the suggestions: its own folder, then the agent starts on the brief. */
    fun startIdea(idea: ProjectIdea) {
        viewModelScope.launch {
            val project = workspace.createProject(idea.title, idea.template)
            openProject(project, initialTask = idea.brief)
        }
    }

    /**
     * What the shared input island sends on the 代码 page: a slash command, a follow-up in the open
     * project, or — with no project open — a request that becomes a project of its own.
     */
    fun submit(text: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("/")) {
            runCommand(trimmed)
            return
        }
        if (_current.value != null) send(trimmed) else startFromPrompt(trimmed)
    }

    /* ---------------- slash commands ---------------- */

    /**
     * The handful of commands worth having on a phone. Anything unrecognised is treated as an
     * ordinary request rather than an error — a message that happens to start with "/" is far more
     * likely to be a path than a typo'd command.
     */
    private fun runCommand(input: String) {
        val name = input.drop(1).substringBefore(' ').lowercase()
        val rest = input.drop(1).substringAfter(' ', "").trim()
        // Help is not conversation, so it opens a sheet rather than landing in a transcript — which
        // on the home screen, with no project open, would have had nowhere to render at all.
        if (name == "help" || name == "?") {
            _showHelp.value = true
            return
        }
        if (name == "new") {
            if (rest.isNotBlank()) startFromPrompt(rest) else closeProject()
            return
        }
        if (name !in PROJECT_COMMANDS) {
            // Not a command we know: almost certainly a path or a sentence, so send it as one.
            if (_current.value != null) send(input) else startFromPrompt(input)
            return
        }
        if (_current.value == null) {
            _notice.value = LocaleHelper.string(context, R.string.code_cmd_needs_project)
            return
        }
        when (name) {
            "clear" -> clearTranscript()
            "undo" -> undo()
            "compact" -> compact()
            "init" -> initInstructions()
        }
    }

    fun dismissHelp() {
        _showHelp.value = false
    }

    /** Appends a note from the app itself — command output, not something the model said. */
    private fun note(text: String) {
        if (_current.value == null) {
            _notice.value = text
            return
        }
        _turns.update { it + CodeTurn(CodeRole.NOTE, text) }
        persist()
    }

    /** Writes the project's CONE.md by having the agent read the project first. */
    private fun initInstructions() {
        send(LocaleHelper.string(context, R.string.code_cmd_init_task, workspace.instructionsName()))
    }

    /** Replaces the transcript with a summary, keeping the thread but freeing the context. */
    private fun compact() {
        val project = _current.value ?: return
        if (_running.value || _turns.value.isEmpty()) return
        _running.value = true
        job = viewModelScope.launch {
            agent.summarize(_turns.value).fold(
                onSuccess = { summary ->
                    _turns.value = listOf(
                        CodeTurn(
                            role = CodeRole.ASSISTANT,
                            text = LocaleHelper.string(context, R.string.code_cmd_compact_done) + "\n\n" + summary,
                        ),
                    )
                    workspace.saveTranscript(project, _turns.value)
                },
                onFailure = { _notice.value = it.message },
            )
            _running.value = false
        }
    }

    /** Rolls the files back to the snapshot taken before the last request. */
    private fun undo() {
        val last = _turns.value.lastOrNull { it.role == CodeRole.USER && it.checkpoint != null }
        if (last?.checkpoint == null) {
            _notice.value = LocaleHelper.string(context, R.string.code_no_checkpoint)
            return
        }
        restoreCheckpoint(last.checkpoint)
    }

    /** Restores the project's files to a snapshot; the transcript is left alone as the record. */
    fun restoreCheckpoint(id: String) {
        val project = _current.value ?: return
        if (_running.value) return
        viewModelScope.launch {
            workspace.restore(project, id).fold(
                onSuccess = {
                    refreshTree()
                    reloadOpenFile()
                    note(LocaleHelper.string(context, R.string.code_restored))
                },
                onFailure = { _notice.value = it.message },
            )
        }
    }

    fun canRestore(id: String?): Boolean {
        val project = _current.value ?: return false
        return id != null && workspace.hasCheckpoint(project, id)
    }

    /**
     * Starts a project straight from what the user typed. The folder is created empty — the agent is
     * about to write the real files, and a template's placeholder would only be in the way.
     */
    fun startFromPrompt(text: String) {
        val task = text.trim()
        if (task.isBlank()) return
        viewModelScope.launch {
            val project = workspace.createProject(projectNameFrom(task), ProjectTemplate.BLANK, seed = false)
            openProject(project, initialTask = task)
        }
    }

    /**
     * A folder name from a request like 「帮我做一个番茄钟网页」 → 「番茄钟网页」. Deliberately crude: the
     * name is visible in a file manager from the first second, and the user can rename the project
     * once the agent has told them what it actually built.
     */
    private fun projectNameFrom(task: String): String {
        var name = task.lines().first().trim()
        LEAD_INS.forEach { name = name.removePrefix(it).trim() }
        name = name.trimStart('，', ',', '：', ':', ' ')
        return name.take(16).ifBlank { LocaleHelper.string(context, R.string.code_default_name) }
    }

    fun switchModel(providerId: Long, modelId: String) {
        viewModelScope.launch { settingsRepository.selectChatModel(providerId, modelId) }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /* ---------------- projects ---------------- */

    fun createProject(name: String, template: ProjectTemplate) {
        viewModelScope.launch { openProject(workspace.createProject(name, template)) }
    }

    fun openProject(project: CodeProject, initialTask: String? = null) {
        stop()
        _current.value = project
        _open.value = null
        _pane.value = CodePane.NONE
        viewModelScope.launch {
            _turns.value = workspace.loadTranscript(project)
            refreshTree()
            // Started from a suggestion or a typed request: the workspace opens already working.
            if (initialTask != null) send(initialTask)
        }
    }

    fun closeProject() {
        stop()
        persist()
        _current.value = null
        _open.value = null
        _pane.value = CodePane.NONE
        _turns.value = emptyList()
        viewModelScope.launch { workspace.refresh() }
    }

    fun deleteProject(project: CodeProject) {
        viewModelScope.launch {
            if (_current.value?.id == project.id) closeProject()
            workspace.deleteProject(project)
        }
    }

    fun renameProject(name: String) {
        val project = _current.value ?: return
        viewModelScope.launch {
            workspace.renameProject(project, name)
            _current.value = workspace.projects.value.firstOrNull { it.id == project.id } ?: project
        }
    }

    fun clearTranscript() {
        val project = _current.value ?: return
        _turns.value = emptyList()
        viewModelScope.launch { workspace.saveTranscript(project, emptyList()) }
    }

    /* ---------------- files ---------------- */

    fun showPane(pane: CodePane) {
        _pane.value = pane
    }

    fun closePane() {
        _pane.value = if (_open.value != null && _pane.value == CodePane.PREVIEW) CodePane.EDITOR else CodePane.NONE
    }

    private suspend fun refreshTree() {
        val project = _current.value ?: return
        _tree.value = workspace.tree(project)
        _previewFile.value = workspace.previewTarget(project)
        _previewable.value = _previewFile.value != null
        _previewVersion.value += 1
    }

    fun openFile(path: String) {
        val project = _current.value ?: return
        viewModelScope.launch {
            workspace.read(project, path).fold(
                onSuccess = {
                    _open.value = OpenFile(path, it, it)
                    _pane.value = CodePane.EDITOR
                },
                onFailure = { _notice.value = it.message },
            )
        }
    }

    /**
     * Closing commits: leaving the editor with unsaved text and silently losing it is not a trade
     * anyone would choose, and on a phone the editor is closed by the system back gesture as often
     * as by the button — there is no reliable moment to ask.
     */
    fun closeFile() {
        val file = _open.value
        _open.value = null
        _pane.value = CodePane.NONE
        if (file != null && file.dirty) {
            val project = _current.value ?: return
            viewModelScope.launch {
                workspace.write(project, file.path, file.buffer)
                    .onFailure { _notice.value = it.message }
                refreshTree()
            }
        }
    }

    fun editBuffer(text: String) {
        _open.update { it?.copy(buffer = text) }
    }

    fun saveFile() {
        val project = _current.value ?: return
        val file = _open.value ?: return
        viewModelScope.launch {
            workspace.write(project, file.path, file.buffer).fold(
                onSuccess = {
                    _open.value = file.copy(saved = file.buffer)
                    refreshTree()
                },
                onFailure = { _notice.value = it.message },
            )
        }
    }

    fun createFile(path: String) {
        val project = _current.value ?: return
        val clean = path.trim().trim('/')
        if (clean.isEmpty()) return
        viewModelScope.launch {
            if (workspace.exists(project, clean)) {
                _notice.value = LocaleHelper.string(context, R.string.code_err_exists, clean)
                return@launch
            }
            workspace.write(project, clean, "").fold(
                onSuccess = {
                    refreshTree()
                    openFile(clean)
                },
                onFailure = { _notice.value = it.message },
            )
        }
    }

    fun deleteFile(path: String) {
        val project = _current.value ?: return
        viewModelScope.launch {
            workspace.delete(project, path).fold(
                onSuccess = {
                    if (_open.value?.path == path) closeFile()
                    refreshTree()
                },
                onFailure = { _notice.value = it.message },
            )
        }
    }

    /* ---------------- the agent ---------------- */

    /**
     * Runs one request. [planOverride] forces plan mode off for a run that has already been through
     * it — the approval itself is the thing plan mode was waiting for.
     */
    fun send(task: String, planOverride: Boolean? = null) {
        val project = _current.value ?: return
        val trimmed = task.trim()
        if (trimmed.isBlank() || _running.value) return

        val history = _turns.value
        _turns.update { it + CodeTurn(CodeRole.USER, trimmed) }
        _running.value = true
        _awaitingAnswer.value = false
        _awaitingPlan.value = false
        val plan = planOverride ?: planMode.value
        job = viewModelScope.launch {
            // Snapshot before the agent touches anything, so this exact request can be taken back.
            val checkpoint = workspace.checkpoint(project)
            if (checkpoint != null) {
                _turns.update { list ->
                    val index = list.indexOfLast { it.role == CodeRole.USER }
                    if (index == -1) list else list.toMutableList().also {
                        it[index] = it[index].copy(checkpoint = checkpoint)
                    }
                }
            }
            agent.run(project, trimmed, history, planMode = plan).collect { event ->
                when (event) {
                    is CodeEvent.RoundStart ->
                        _turns.update { it + CodeTurn(CodeRole.ASSISTANT, streaming = true) }

                    is CodeEvent.Text -> updateLastAssistant { it.copy(text = event.full) }

                    is CodeEvent.Tool -> {
                        updateLastAssistant { it.copy(results = it.results + event.outcome) }
                        onFilesChanged(event.outcome)
                    }

                    is CodeEvent.Finished -> {
                        updateLastAssistant { it.copy(streaming = false, tokens = event.tokens) }
                        finish()
                    }

                    // Stopped on purpose, waiting on the user: a question to answer, or a plan to
                    // approve. Neither is a failure and neither is a completed task.
                    is CodeEvent.Asked -> {
                        updateLastAssistant { it.copy(streaming = false, tokens = event.tokens) }
                        _awaitingAnswer.value = true
                        finish()
                    }

                    is CodeEvent.Planned -> {
                        updateLastAssistant { it.copy(streaming = false, tokens = event.tokens) }
                        _awaitingPlan.value = true
                        finish()
                    }

                    is CodeEvent.Failed -> {
                        updateLastAssistant { it.copy(streaming = false) }
                        _turns.update { it + CodeTurn(CodeRole.ERROR, event.message) }
                        finish()
                    }
                }
            }
            // The flow can also end without a terminal event if the model produced nothing at all.
            if (_running.value) finish()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (_running.value) {
            updateLastAssistant { it.copy(streaming = false) }
            _running.value = false
            persist()
        }
    }

    private suspend fun finish() {
        _running.value = false
        refreshTree()
        persist()
    }

    /**
     * Keeps the editor honest while the agent works: a file the agent just rewrote is reloaded
     * underneath the user — unless they have unsaved edits of their own, which are never discarded.
     */
    private suspend fun onFilesChanged(outcome: ToolOutcome) {
        if (outcome.tool !in WRITING_TOOLS || !outcome.ok) return
        refreshTree()
        val file = _open.value ?: return
        if (file.path != outcome.target) return
        reloadOpenFile()
    }

    /** Pulls the editor back in line with disk, unless the user has unsaved edits of their own. */
    private suspend fun reloadOpenFile() {
        val project = _current.value ?: return
        val file = _open.value ?: return
        if (file.dirty) return
        workspace.read(project, file.path).onSuccess { _open.value = OpenFile(file.path, it, it) }
    }

    private fun updateLastAssistant(transform: (CodeTurn) -> CodeTurn) {
        _turns.update { list ->
            val index = list.indexOfLast { it.role == CodeRole.ASSISTANT }
            if (index == -1) list else list.toMutableList().also { it[index] = transform(it[index]) }
        }
    }

    private fun persist() {
        val project = _current.value ?: return
        val snapshot = _turns.value
        viewModelScope.launch { workspace.saveTranscript(project, snapshot) }
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }

    private companion object {
        val WRITING_TOOLS = setOf("write", "edit", "delete", "rename")

        /** Commands that only mean anything inside an open project. */
        val PROJECT_COMMANDS = setOf("clear", "undo", "compact", "init")

        /** Phrasings people open a request with, stripped so the folder name is the subject itself. */
        val LEAD_INS = listOf(
            "帮我写一个", "帮我写个", "帮我做一个", "帮我做个", "帮我", "请帮我",
            "我想做一个", "我想做个", "我想要一个", "我要做一个", "我想写一个",
            "写一个", "写个", "做一个", "做个", "生成一个", "创建一个", "开发一个",
            "build a", "build me a", "create a", "make a", "write a",
        )
    }
}
