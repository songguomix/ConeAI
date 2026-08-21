package com.cone.agent.agent

import com.cone.agent.agent.action.PlannedAction
import com.cone.agent.core.Constants
import com.cone.agent.domain.model.TaskStatus

/** A high-risk action awaiting explicit user confirmation. */
data class PendingConfirmation(
    val reason: String,
    val action: PlannedAction,
    val targetLabel: String?,
)

/** The single source of truth for the running agent, observed by the UI and floating control. */
data class AgentRuntimeState(
    val status: TaskStatus = TaskStatus.IDLE,
    val instruction: String = "",
    val stepIndex: Int = 0,
    val maxSteps: Int = Constants.DEFAULT_MAX_STEPS,
    val currentThought: String = "",
    val currentAction: String = "",
    val plan: List<String> = emptyList(),
    val pending: PendingConfirmation? = null,
    /** 计划模式：等待用户确认的总体计划；非空时不执行任何动作，确认后才进入执行循环。 */
    val pendingPlan: List<String>? = null,
    /** 本次任务已开始真实屏幕操作（非无头动作）——语音助手悬浮窗见此标志即让出屏幕。 */
    val usingScreen: Boolean = false,
    /** 任务成功后的完成说明，供语音助手胶囊直接显示与朗读。 */
    val summary: String = "",
    val lastError: String? = null,
    /** Tokens the provider reported across all model calls in the current task. */
    val cumulativeTokens: Int = 0,
) {
    val isBusy: Boolean
        get() = status == TaskStatus.RUNNING ||
            status == TaskStatus.OBSERVING ||
            status == TaskStatus.WAITING_CONFIRM ||
            status == TaskStatus.PAUSED

    val isPaused: Boolean get() = status == TaskStatus.PAUSED

    val displayStep: Int get() = (stepIndex + 1).coerceAtMost(maxSteps)
}
