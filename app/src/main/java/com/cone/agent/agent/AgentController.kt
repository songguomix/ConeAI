package com.cone.agent.agent

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import com.cone.agent.agent.action.ActionPlan
import com.cone.agent.agent.action.ActionType
import com.cone.agent.agent.action.PlannedAction
import com.cone.agent.agent.action.ThinkEvent
import androidx.annotation.StringRes
import com.cone.agent.R
import com.cone.agent.core.Constants
import com.cone.agent.core.LocaleHelper
import com.cone.agent.data.repository.ChatRepository
import com.cone.agent.data.repository.SettingsRepository
import com.cone.agent.domain.model.ConversationMode
import com.cone.agent.domain.model.Sender
import com.cone.agent.domain.model.TaskStatus
import com.cone.agent.service.AgentForegroundService
import com.cone.agent.service.FloatingControlService
import com.cone.agent.vision.AccessibilityBridge
import com.cone.agent.vision.ScreenSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

/**
 * The brain that runs the Observe → Think → Act loop, owns the runtime state, and enforces the
 * user-priority and high-risk-confirmation rules. Lives for the whole process; the
 * [AgentForegroundService] keeps the process alive while a task runs.
 */
@Singleton
class AgentController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: AgentEngine,
    private val executor: ActionExecutor,
    private val riskGuard: RiskGuard,
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
    private val bridge: AccessibilityBridge,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(AgentRuntimeState())
    val state: StateFlow<AgentRuntimeState> = _state.asStateFlow()

    private val paused = MutableStateFlow(false)

    /**
     * 语音助手悬浮窗是否正敞开在屏幕上。胶囊敞开时无头动作（信息查询/闹钟/手电筒…）照常执行，
     * 第一个需要真实屏幕的动作会先等胶囊让位再重新观察——规划所依据的截图里叠着胶囊，坐标不可信。
     */
    private val assistantOverlay = MutableStateFlow(false)

    private var loopJob: Job? = null
    // The in-flight OBSERVE+THINK+ACT of the current step (think and act overlap: actions execute
    // while the response still streams); cancelled on pause/takeover so resuming is instant instead
    // of waiting for a now-stale capture + LLM stream to finish.
    @Volatile private var stepJob: Job? = null
    private var pendingConfirm: CompletableDeferred<Boolean>? = null
    private var pendingPlanConfirm: CompletableDeferred<Boolean>? = null
    private var failures = 0

    /**
     * Set when the user takes over via the floating control's "接管" button after we captured the
     * current observation. The pending action was planned against the *pre-takeover* screen, so once
     * the user resumes we must throw it away and observe again instead of clicking a stale target.
     */
    @Volatile private var userTookOverSinceObserve = false

    /** Agent log lines always belong to the 智能体 (AGENT) conversation, never the 问答 thread. */
    private suspend fun addLog(sender: Sender, text: String, isError: Boolean = false) =
        chat.add(sender, text, ConversationMode.AGENT, isError = isError)

    /** Resolve a string in the user-selected app language (runtime/log lines aren't in Compose). */
    private fun str(@StringRes id: Int, vararg args: Any): String = LocaleHelper.string(context, id, *args)

    /* ---------------- public controls ---------------- */

    fun start(instruction: String) {
        if (loopJob?.isActive == true) return
        if (instruction.isBlank()) return
        paused.value = false
        failures = 0
        userTookOverSinceObserve = false
        AgentForegroundService.start(context)
        FloatingControlService.start(context)
        loopJob = scope.launch { runLoop(instruction.trim()) }
    }

    fun pause() {
        // There's no automatic screen-touch detection any more, so a manual pause is also treated as
        // a takeover: the user may operate the screen while paused, so on 继续 we re-observe instead
        // of replaying a possibly-stale planned action.
        userTookOverSinceObserve = true
        paused.value = true
        // Keep a pending high-risk confirmation on screen (the step now spans think+act, so a blind
        // cancel would tear the dialog down); the takeover flag already forces a rescan afterwards.
        if (_state.value.status != TaskStatus.WAITING_CONFIRM) {
            stepJob?.cancel() // abandon any in-flight observe/think/act so resume is immediate
        }
        if (_state.value.isBusy) update { it.copy(status = TaskStatus.PAUSED) }
    }

    fun resume() {
        paused.value = false
        if (_state.value.status == TaskStatus.PAUSED) update { it.copy(status = TaskStatus.RUNNING) }
    }

    /** Explicit "take over" — identical guarantee to a user touch: stop acting immediately. */
    fun takeOver() {
        val status = _state.value.status
        // Only meaningful while actively driving; don't clobber a pending confirmation or a finished run.
        if (status != TaskStatus.RUNNING && status != TaskStatus.OBSERVING) return
        userTookOverSinceObserve = true
        paused.value = true
        stepJob?.cancel() // abandon any in-flight observe/think so resume is immediate
        update { it.copy(status = TaskStatus.PAUSED, currentAction = str(R.string.agent_status_taken_over)) }
        scope.launch {
            addLog(Sender.SYSTEM, str(R.string.agent_log_takeover))
        }
    }

    fun confirm(approved: Boolean) {
        pendingConfirm?.complete(approved)
    }

    /** 计划模式：用户对总体计划的裁决 —— 同意开始执行，或取消整个任务。 */
    fun confirmPlan(approved: Boolean) {
        pendingPlanConfirm?.complete(approved)
    }

    /** 语音助手胶囊报到/离场（离场即把屏幕让给智能体，见 [assistantOverlay]）。 */
    fun setAssistantOverlayVisible(visible: Boolean) {
        assistantOverlay.value = visible
    }

    /**
     * Clears the per-conversation token tally shown in the usage bar. Called when the user starts or
     * clears a 智能体 conversation: the count otherwise lingers from the previous task (it's only reset
     * when a *new* task begins), so a fresh conversation would still show stale usage. No-op while a
     * task is running so a live count isn't wiped mid-run.
     */
    fun clearUsage() {
        if (!_state.value.isBusy) update { it.copy(cumulativeTokens = 0) }
    }

    /* NOTE: there is intentionally no model-initiated pause. Only the user can pause the task (via
     * the pause button / floating control). The agent must keep driving the task to completion. */

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        pendingConfirm?.complete(false)
        pendingPlanConfirm?.complete(false)
        paused.value = false
        update { it.copy(status = TaskStatus.CANCELLED, currentAction = str(R.string.status_cancelled), pending = null, pendingPlan = null) }
        scope.launch { addLog(Sender.SYSTEM, str(R.string.agent_log_ended)) }
        stopRuntimeServices()
    }

    /* ---------------- the loop ---------------- */

    private suspend fun runLoop(instruction: String) {
        val maxSteps = settings.maxSteps.first()
        update {
            it.copy(
                status = TaskStatus.RUNNING,
                instruction = instruction,
                stepIndex = 0,
                maxSteps = maxSteps,
                plan = emptyList(),
                pending = null,
                pendingPlan = null,
                usingScreen = false,
                summary = "",
                lastError = null,
                currentThought = "",
                currentAction = str(R.string.agent_status_preparing),
                cumulativeTokens = 0,
            )
        }
        addLog(Sender.USER, instruction)
        addLog(
            Sender.SYSTEM,
            if (maxSteps >= Constants.UNLIMITED_MAX_STEPS) {
                str(R.string.agent_log_start_unlimited)
            } else {
                str(R.string.agent_log_start, maxSteps)
            },
        )

        val memory = TaskMemory()
        var step = 0
        try {
            // 计划模式：先只规划不执行，计划经用户确认后才允许进入执行循环。
            if (settings.planModeEnabled.first() && !planPhase(instruction, maxSteps, memory)) return
            while (step < maxSteps) {
                awaitResume()

                // Fresh capture reflects the current screen, so any prior takeover is settled.
                userTookOverSinceObserve = false

                when (val outcome = runStep(instruction, step, maxSteps, memory)) {
                    // Cancelled by pause/takeover, or the rest of the batch went stale (takeover,
                    // rejected confirmation, screen change, tool observation) — loop back: awaitResume
                    // waits for 继续, then a fresh observation replaces the stale plan.
                    StepOutcome.Cancelled, StepOutcome.Rescan -> continue
                    is StepOutcome.Finished -> {
                        succeed(outcome.summary)
                        return
                    }
                    is StepOutcome.NoDecision -> {
                        val maxFailures = Constants.MAX_CONSECUTIVE_FAILURES
                        failures++
                        addLog(
                            Sender.SYSTEM,
                            str(R.string.agent_log_think_fail, failures, maxFailures, outcome.error?.message ?: ""),
                            isError = true,
                        )
                        if (failures >= maxFailures) {
                            fail(str(R.string.agent_fail_no_decision, maxFailures, outcome.error?.message ?: ""))
                            return
                        }
                        delay(900)
                    }
                    is StepOutcome.Failed -> {
                        fail(outcome.message)
                        return
                    }
                    StepOutcome.Advance -> step++
                }
            }
            fail(str(R.string.agent_fail_max_steps, maxSteps))
        } catch (c: CancellationException) {
            update { it.copy(status = TaskStatus.CANCELLED, currentAction = str(R.string.status_cancelled), pending = null, pendingPlan = null) }
            throw c
        } catch (t: Throwable) {
            fail(str(R.string.agent_fail_exception, t.message ?: ""))
        }
    }

    /* ---------------- 计划模式 ---------------- */

    /**
     * 计划模式的前置阶段：观察一次屏幕、让模型产出总体计划（本次响应中的动作全部丢弃、绝不执行），
     * 再把计划交给用户裁决。返回 true 表示计划获确认、可进入执行循环；返回 false 表示任务到此结束
     * （用户取消计划、规划连续失败，或模型判断任务无需执行）。
     */
    private suspend fun planPhase(instruction: String, maxSteps: Int, memory: TaskMemory): Boolean {
        while (true) {
            awaitResume()
            userTookOverSinceObserve = false
            when (val outcome = draftPlan(instruction, maxSteps, memory)) {
                // 暂停/接管打断了规划 — 等「继续」后重新规划。
                StepOutcome.Cancelled, StepOutcome.Rescan -> continue
                // 模型认为任务已经完成（如指令本身无事可做），无需用户确认任何动作。
                is StepOutcome.Finished -> {
                    succeed(outcome.summary)
                    return false
                }
                is StepOutcome.Failed -> {
                    fail(outcome.message)
                    return false
                }
                is StepOutcome.NoDecision -> {
                    val maxFailures = Constants.MAX_CONSECUTIVE_FAILURES
                    failures++
                    addLog(
                        Sender.SYSTEM,
                        str(R.string.agent_log_think_fail, failures, maxFailures, outcome.error?.message ?: ""),
                        isError = true,
                    )
                    if (failures >= maxFailures) {
                        fail(str(R.string.agent_fail_no_decision, maxFailures, outcome.error?.message ?: ""))
                        return false
                    }
                    delay(900)
                }
                StepOutcome.Advance -> {
                    if (!awaitPlanApproval(memory.overallPlan)) {
                        update { it.copy(status = TaskStatus.CANCELLED, currentAction = str(R.string.status_cancelled), pending = null, pendingPlan = null) }
                        addLog(Sender.SYSTEM, str(R.string.agent_log_plan_rejected))
                        stopRuntimeServices()
                        return false
                    }
                    addLog(Sender.SYSTEM, str(R.string.agent_log_plan_approved))
                    memory.history.add("用户已确认总体计划，开始执行")
                    failures = 0
                    return true
                }
            }
        }
    }

    /**
     * One planning-only step: observe + think, executing nothing. The model's plan outline lands in
     * [TaskMemory.overallPlan] (falling back to the batch's action descriptions when the response
     * carries no plan array), so the execution loop later reasons under the user-approved plan.
     * Runs as a [stepJob] child like a normal step, so pause/takeover cancels it the same way.
     */
    private suspend fun draftPlan(
        instruction: String,
        maxSteps: Int,
        memory: TaskMemory,
    ): StepOutcome = coroutineScope {
        val body = async {
            update { it.copy(status = TaskStatus.OBSERVING, stepIndex = 0, currentAction = str(R.string.agent_status_observing_screen)) }
            val snapshot = engine.observe()
            ensureActive()
            update { it.copy(status = TaskStatus.RUNNING, currentAction = str(R.string.agent_status_planning)) }

            var completed: ActionPlan? = null
            var streamError: Throwable? = null
            try {
                engine.thinkStream(instruction, snapshot, 0, maxSteps, memory.history, emptyList(), "")
                    .collect { event ->
                        when (event) {
                            is ThinkEvent.Thought -> noteThought(event.text, memory)
                            is ThinkEvent.PlanOutline -> notePlan(event.steps, memory)
                            is ThinkEvent.Action -> Unit // planning only — nothing may run before approval
                            is ThinkEvent.Completed -> {
                                completed = event.plan
                                event.totalTokens?.let { tk ->
                                    update { state -> state.copy(cumulativeTokens = state.cumulativeTokens + tk) }
                                }
                            }
                        }
                    }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                streamError = t
            }

            val plan = completed
            when {
                streamError != null -> StepOutcome.NoDecision(streamError)
                plan == null -> StepOutcome.NoDecision(null)
                plan.done && plan.actions.none { it.type != ActionType.FINISH } ->
                    StepOutcome.Finished(plan.summary.ifBlank { str(R.string.agent_done_default) })
                else -> {
                    if (memory.overallPlan.isEmpty()) {
                        val steps = plan.actions
                            .filter { it.type != ActionType.FINISH }
                            .map { it.describe(context) }
                        if (steps.isEmpty()) return@async StepOutcome.NoDecision(null)
                        notePlan(steps, memory)
                    }
                    StepOutcome.Advance
                }
            }
        }
        stepJob = body
        try {
            body.await()
        } catch (c: CancellationException) {
            coroutineContext.ensureActive()
            StepOutcome.Cancelled
        } finally {
            stepJob = null
        }
    }

    /** Blocks until the user rules on the drafted plan; true = 开始执行, false = 取消任务. */
    private suspend fun awaitPlanApproval(steps: List<String>): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingPlanConfirm = deferred
        update {
            it.copy(
                status = TaskStatus.WAITING_CONFIRM,
                pendingPlan = steps,
                currentAction = str(R.string.agent_status_plan_waiting),
            )
        }
        addLog(Sender.SYSTEM, str(R.string.agent_log_plan_waiting))
        val approved = try {
            deferred.await()
        } finally {
            pendingPlanConfirm = null
        }
        update { it.copy(pendingPlan = null, status = if (approved) TaskStatus.RUNNING else it.status) }
        return approved
    }

    /* ---------------- helpers ---------------- */

    /** Cross-step task context: executed-action history plus the step-0 plan and latest thought. */
    private class TaskMemory {
        val history = ArrayList<String>()
        var overallPlan: List<String> = emptyList()
        var lastThought = ""
    }

    private sealed interface StepOutcome {
        /** Pause/takeover cancelled the step mid-flight; wait for 继续 and re-observe. */
        data object Cancelled : StepOutcome

        /** The rest of the batch went stale — re-observe without counting a failure. */
        data object Rescan : StepOutcome

        /** Batch fully executed and the task isn't done — move to the next step. */
        data object Advance : StepOutcome

        data class Finished(val summary: String) : StepOutcome

        /** No usable decision this step (stream/parse failure or an empty do-nothing plan). */
        data class NoDecision(val error: Throwable?) : StepOutcome

        /** The failure budget ran out mid-batch — end the whole task with [message]. */
        data class Failed(val message: String) : StepOutcome
    }

    private sealed interface ActionOutcome {
        data object Executed : ActionOutcome
        data object Rescan : ActionOutcome
        data class Finish(val summary: String) : ActionOutcome

        /** Too many consecutive execution failures — abort the task with [message]. */
        data class Abort(val message: String) : ActionOutcome
    }

    /** Thrown by the collector to cut off a stale stream (rescan) without failing the step. */
    private class AbortStream : RuntimeException()

    /**
     * One full streamed step: OBSERVE, then THINK and ACT overlapped — every action executes the
     * moment it is parsed out of the model's still-streaming response, so decode time and device
     * time run in parallel instead of back-to-back. All guards of the old batch loop apply per
     * action (user priority, screen-change token, high-risk confirmation). The whole step runs in a
     * child job tracked by [stepJob] so pause/takeover cancels observation, stream and execution at
     * once.
     */
    private suspend fun runStep(
        instruction: String,
        step: Int,
        maxSteps: Int,
        memory: TaskMemory,
    ): StepOutcome = coroutineScope {
        val body = async {
            update { it.copy(status = TaskStatus.OBSERVING, stepIndex = step, currentAction = str(R.string.agent_status_observing_screen)) }
            val snapshot = engine.observe()
            ensureActive()
            update { it.copy(currentAction = str(R.string.agent_status_thinking)) }

            var executed = 0
            var lastType: ActionType? = null
            var rescan = false
            var abortMessage: String? = null
            var finishSummary: String? = null
            var completed: ActionPlan? = null
            var streamError: Throwable? = null

            try {
                engine.thinkStream(instruction, snapshot, step, maxSteps, memory.history, memory.overallPlan, memory.lastThought)
                    .collect { event ->
                        when (event) {
                            is ThinkEvent.Thought -> noteThought(event.text, memory)
                            // 计划模式下已获用户确认的总体计划保持权威，不被步内计划覆盖。
                            is ThinkEvent.PlanOutline -> if (step == 0 && memory.overallPlan.isEmpty()) notePlan(event.steps, memory)
                            is ThinkEvent.Action -> {
                                if (finishSummary != null) return@collect // drain to Completed for the summary
                                when (val r = performStreamedAction(event.action, snapshot, step, executed, memory)) {
                                    ActionOutcome.Executed -> {
                                        executed++
                                        lastType = event.action.type
                                    }
                                    ActionOutcome.Rescan -> {
                                        rescan = true
                                        throw AbortStream() // stop decoding a plan we'll discard anyway
                                    }
                                    is ActionOutcome.Abort -> {
                                        abortMessage = r.message
                                        throw AbortStream()
                                    }
                                    is ActionOutcome.Finish -> finishSummary = r.summary
                                }
                            }
                            is ThinkEvent.Completed -> {
                                completed = event.plan
                                event.totalTokens?.let { tk ->
                                    update { state -> state.copy(cumulativeTokens = state.cumulativeTokens + tk) }
                                }
                            }
                        }
                    }
            } catch (e: AbortStream) {
                // Stale batch cut short — fall through to Rescan.
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                streamError = t
            }

            val plan = completed
            when {
                finishSummary != null -> StepOutcome.Finished(
                    plan?.summary.orEmpty()
                        .ifBlank { finishSummary.orEmpty() }
                        .ifBlank { str(R.string.agent_done_default) },
                )
                abortMessage != null -> StepOutcome.Failed(abortMessage.orEmpty())
                rescan -> StepOutcome.Rescan
                streamError != null -> StepOutcome.NoDecision(streamError)
                plan == null -> StepOutcome.NoDecision(null)
                plan.done -> StepOutcome.Finished(plan.summary.ifBlank { str(R.string.agent_done_default) })
                // A plan with no actions and not finished is as useless as a parse failure — count it
                // so we don't spin forever re-observing the same screen.
                executed == 0 && plan.actions.isEmpty() -> StepOutcome.NoDecision(null)
                else -> {
                    // Batch over: wait only as long as the screen needs to settle before re-observing.
                    lastType?.let { settleAfter(it) }
                    StepOutcome.Advance
                }
            }
        }
        stepJob = body
        try {
            body.await()
        } catch (c: CancellationException) {
            coroutineContext.ensureActive() // whole-task cancellation (stop) must still propagate
            StepOutcome.Cancelled
        } finally {
            stepJob = null
        }
    }

    /** Runs one streamed action with the full guard set. [index] is its position in the batch. */
    private suspend fun performStreamedAction(
        planned: PlannedAction,
        snapshot: ScreenSnapshot,
        step: Int,
        index: Int,
        memory: TaskMemory,
    ): ActionOutcome {
        awaitResume()
        if (userTookOverSinceObserve) {
            addLog(Sender.SYSTEM, str(R.string.agent_log_takeover_rescan))
            return ActionOutcome.Rescan
        }
        // If a previous action in this batch (or an external event) moved us to a new window/screen,
        // the rest of the plan was built for a screen that's gone: stop and re-observe. Only checked
        // from the 2nd action on, so the first always runs and the task keeps making progress.
        if (index > 0 && bridge.screenChangeToken() != snapshot.screenChangeToken) {
            addLog(Sender.SYSTEM, str(R.string.agent_log_screen_changed))
            return ActionOutcome.Rescan
        }
        // Model coordinates are in downscaled image space; map them back to real pixels.
        val action = planned.toScreenSpace(snapshot.scale)
        update { it.copy(status = TaskStatus.RUNNING, currentAction = action.describe(context)) }

        // 语音悬浮窗礼让：无头动作（信息查询/闹钟/手电筒…）在胶囊敞开时照常执行、胶囊不退出；
        // 第一个需要真实屏幕的动作到来时标记 usingScreen（胶囊据此让位），若胶囊确实敞开着，
        // 等它离场后作废本批重新观察——原截图里叠着胶囊，这批坐标不可信。
        if (action.type !in ActionType.HEADLESS && !_state.value.usingScreen) {
            update { it.copy(usingScreen = true) }
            if (assistantOverlay.value) {
                withTimeoutOrNull(OVERLAY_YIELD_TIMEOUT_MS) { assistantOverlay.first { !it } }
                addLog(Sender.SYSTEM, str(R.string.agent_log_overlay_yield))
                return ActionOutcome.Rescan
            }
        }

        // FINISH anywhere in the batch ends the whole task (summary finalised once the stream ends).
        if (action.type == ActionType.FINISH || action.done) {
            return ActionOutcome.Finish(action.summary)
        }

        // HIGH-RISK CONFIRMATION (per action; labels come from the batch's screenshot). This suspends
        // the stream collector while the user decides, so the SSE read pauses too; a very slow
        // confirmation could idle-timeout the socket, which merely surfaces as a rescan on the
        // remaining actions (already-executed ones stand) — the task stays correct.
        val targetLabel = nearestLabel(snapshot, action)
        val risk = riskGuard.assess(action, targetLabel)
        if (risk != null && !settings.autoConfirmHighRisk.first()) {
            val approved = awaitConfirmation(risk, action, targetLabel)
            if (!approved) {
                addLog(Sender.SYSTEM, str(R.string.agent_log_rejected))
                memory.history.add("第${step + 1}步动作${index + 1}：用户拒绝执行 ${action.describe(context)}")
                // The rejected tap breaks the planned chain — drop the rest and re-observe.
                return ActionOutcome.Rescan
            }
            addLog(Sender.SYSTEM, str(R.string.agent_log_confirmed))
        }

        awaitResume()
        if (userTookOverSinceObserve) {
            addLog(Sender.SYSTEM, str(R.string.agent_log_takeover_rescan))
            return ActionOutcome.Rescan
        }
        update { it.copy(status = TaskStatus.RUNNING) }
        val result = executor.execute(action, allowWebSearch = false)
        val tag = if (result.success) str(R.string.tag_ok) else str(R.string.status_failed)
        addLog(Sender.TOOL, "${action.describe(context)} → $tag：${result.message}", isError = !result.success)
        memory.history.add("第${step + 1}步动作${index + 1}：${action.describe(context)} → $tag${if (result.success) "" else "：${result.message}"}")

        // 回退：某个动作执行失败后，本批剩余动作赖以成立的画面前提已经不可信（它们假设这一步成功了），
        // 继续盲跑只会在错误的地方点击/输入。作废剩余计划、重新观察，让模型基于失败记录换思路；同时
        // 计入连续失败计数——环境彻底坏掉（如无障碍服务失效）时不会陷入「失败→重观察」的死循环。
        if (!result.success) {
            val maxFailures = Constants.MAX_CONSECUTIVE_FAILURES
            failures++
            addLog(Sender.SYSTEM, str(R.string.agent_log_action_fail, failures, maxFailures), isError = true)
            if (failures >= maxFailures) return ActionOutcome.Abort(str(R.string.agent_fail_actions, maxFailures))
            return ActionOutcome.Rescan
        }
        // Real progress on the device is what clears the failure budget — a parseable decision alone
        // no longer does, so think-parse loops and repeated failed taps both hit the cap eventually.
        failures = 0

        // Headless info tools (weather / news) hand back free text the model must read next:
        // record the full result in history and re-think, so the next step reasons with it.
        if (result.observation != null) {
            memory.history.add(result.observation)
            addLog(Sender.TOOL, result.observation)
            return ActionOutcome.Rescan
        }

        // Opening the built-in browser or the share sheet switches the foreground screen, so anything
        // the model batched after it was planned against the old screen: stop here and re-observe
        // instead of replaying a stale plan.
        if (action.type == ActionType.WEB_SEARCH ||
            action.type == ActionType.OPEN_URL ||
            action.type == ActionType.OPEN_BROWSER ||
            action.type == ActionType.SHARE ||
            action.type == ActionType.NAVIGATE ||
            action.type == ActionType.PLAY_MUSIC ||
            action.type == ActionType.SHOPPING ||
            action.type == ActionType.CALL ||
            action.type == ActionType.SMS ||
            action.type == ActionType.EMAIL ||
            action.type == ActionType.OPEN_SETTINGS
        ) {
            settleAfter(action.type)
            return ActionOutcome.Rescan
        }

        // Pace consecutive taps; the batch-final settle runs once the stream ends (see runStep).
        delay(Constants.SMOOTH_ACTION_GAP_MS)
        return ActionOutcome.Executed
    }

    private suspend fun noteThought(thought: String, memory: TaskMemory) {
        memory.lastThought = thought
        update { it.copy(currentThought = thought) }
        addLog(Sender.AGENT, thought)
    }

    private suspend fun notePlan(steps: List<String>, memory: TaskMemory) {
        memory.overallPlan = steps
        update { it.copy(plan = steps) }
        addLog(
            Sender.AGENT,
            str(R.string.agent_log_plan_prefix) + "\n" +
                steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
        )
    }

    /**
     * Adaptive settle before re-observing: wait a small floor (so the transition can start), then
     * return as soon as the screen has been quiet for [Constants.SCREEN_QUIET_MS] — capped by the
     * per-action maximum. Fast screens (a checkbox tap that changes no window) proceed almost
     * immediately; slow ones (launching an app) still get their full budget. This cuts the flat
     * per-step wait the loop used to always pay, without ever acting on a mid-transition screen.
     */
    private suspend fun settleAfter(type: ActionType) {
        val (minMs, maxMs) = settleWindowFor(type)
        if (minMs > 0L) delay(minMs)
        if (maxMs <= minMs) return
        val deadline = SystemClock.uptimeMillis() + (maxMs - minMs)
        while (SystemClock.uptimeMillis() < deadline) {
            if (SystemClock.uptimeMillis() - bridge.screenChangeAtMs() >= Constants.SCREEN_QUIET_MS) return
            delay(Constants.SCREEN_POLL_MS)
        }
    }

    /**
     * (floor, ceiling) settle budget per action. Taps/inputs repaint fast; launching an app or the
     * browser needs a bigger floor because a page's *content* loads without a window-state event that
     * the adaptive quiet-check could see.
     */
    private fun settleWindowFor(type: ActionType): Pair<Long, Long> = when (type) {
        ActionType.OPEN_APP -> 300L to 900L
        ActionType.SHARE, ActionType.CALL, ActionType.SMS, ActionType.EMAIL, ActionType.OPEN_SETTINGS -> 400L to 1200L
        ActionType.NAVIGATE, ActionType.PLAY_MUSIC, ActionType.SHOPPING -> 600L to 1500L
        ActionType.WEB_SEARCH, ActionType.OPEN_URL, ActionType.OPEN_BROWSER -> 1300L to 2200L
        ActionType.BACK, ActionType.HOME, ActionType.RECENT_APPS -> 150L to 550L
        ActionType.SWIPE, ActionType.SCROLL -> 120L to 450L
        ActionType.CLICK, ActionType.DOUBLE_CLICK, ActionType.LONG_CLICK, ActionType.INPUT_TEXT -> 90L to 350L
        ActionType.CAPTURE_SCREEN -> 0L to 150L
        ActionType.WAIT -> 0L to 0L
        else -> 0L to Constants.OBSERVE_SETTLE_MS
    }

    private suspend fun awaitResume() {
        paused.first { !it }
    }

    private suspend fun awaitConfirmation(
        reason: String,
        action: PlannedAction,
        targetLabel: String?,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirm = deferred
        update {
            it.copy(
                status = TaskStatus.WAITING_CONFIRM,
                pending = PendingConfirmation(reason, action, targetLabel),
                currentAction = str(R.string.agent_status_awaiting, action.describe(context)),
            )
        }
        addLog(Sender.SYSTEM, "⚠️ $reason", isError = true)
        val approved = try {
            deferred.await()
        } finally {
            pendingConfirm = null
        }
        update { it.copy(pending = null, status = TaskStatus.RUNNING) }
        return approved
    }

    private suspend fun succeed(summary: String) {
        update { it.copy(status = TaskStatus.SUCCEEDED, currentAction = str(R.string.agent_status_done), pending = null, pendingPlan = null, summary = summary) }
        addLog(Sender.SYSTEM, "✅ $summary")
        stopRuntimeServices()
    }

    private suspend fun fail(message: String) {
        update { it.copy(status = TaskStatus.FAILED, lastError = message, currentAction = str(R.string.agent_status_failed), pending = null, pendingPlan = null) }
        addLog(Sender.SYSTEM, "❌ $message", isError = true)
        stopRuntimeServices()
    }

    /** Rescales image-space coordinates returned by the model into real screen pixels. */
    private fun PlannedAction.toScreenSpace(scale: Float): PlannedAction {
        if (scale == 1f) return this
        fun map(value: Int?): Int? = value?.let { (it * scale).toInt() }
        return copy(x = map(x), y = map(y), x2 = map(x2), y2 = map(y2))
    }

    /**
     * The text evidence for the tap the RiskGuard judges. Containment first (smallest labelled
     * element under the point); if the target is a bare icon with no label of its own, fall back to
     * the nearest labelled element within ~48dp — icon buttons usually sit next to their caption,
     * and without this fallback a label-less 支付 button would bypass the confirmation entirely.
     */
    private fun nearestLabel(snapshot: ScreenSnapshot, action: PlannedAction): String? {
        val x = action.x ?: return null
        val y = action.y ?: return null
        // Locally OCR'd text is device evidence just like a control label, and it is the *only*
        // evidence for anything the accessibility tree can't see: a 支付 button drawn inside a
        // WebView or canvas used to reach the executor with no label at all, silently skipping the
        // high-risk confirmation. Both sources are in real screen pixels, as is [action] here.
        // A label-less 支付 button still names itself in its view id (btn_pay), so the hint counts as
        // evidence too — it is device-derived like everything else here, never model-authored.
        val labelled = snapshot.uiElements
            .map { it.label.ifBlank { it.semanticHint } to it.bounds }
            .filter { it.first.isNotBlank() } +
            snapshot.ocrElements.map { it.text to it.bounds }
        labelled
            .filter { (_, bounds) -> bounds.contains(x, y) }
            .minByOrNull { (_, bounds) -> bounds.width() * bounds.height() }
            ?.let { return it.first }
        val radius = context.resources.displayMetrics.density * 48f
        return labelled
            .map { (label, bounds) -> label to bounds.distanceTo(x, y) }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.first
    }

    /** Distance from a point to a rect's edge (0 when inside). */
    private fun Rect.distanceTo(x: Int, y: Int): Float {
        val dx = when { x < left -> left - x; x > right -> x - right; else -> 0 }
        val dy = when { y < top -> top - y; y > bottom -> y - bottom; else -> 0 }
        return hypot(dx.toFloat(), dy.toFloat())
    }

    /**
     * Tears down both runtime services on every terminal path (success/failure/cancel/stop) so the
     * floating control center never lingers on screen after the task is over — previously only the
     * user-driven [stop] dismissed it, leaving a stuck overlay when a task finished on its own.
     */
    private fun stopRuntimeServices() {
        runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
        runCatching { FloatingControlService.stop(context) }
    }

    private inline fun update(block: (AgentRuntimeState) -> AgentRuntimeState) {
        _state.value = block(_state.value)
    }

    private companion object {
        /** How long to wait for the assistant pill to dismiss before re-observing anyway. */
        const val OVERLAY_YIELD_TIMEOUT_MS = 2_000L
    }
}
