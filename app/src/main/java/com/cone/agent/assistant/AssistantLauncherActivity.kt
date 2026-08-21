package com.cone.agent.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.cone.agent.agent.AgentController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Invisible bridge between the assistant popup and the running agent.
 *
 * 屏幕捕获不再是启动前提：简单的接口直达任务（打开应用、导航、播放音乐、打电话…）完全不需要
 * 屏幕能力，弹一次授权只会打断语音体验。智能体自己按已授权的能力自适应——已授权屏幕捕获就带
 * 完整视觉执行；只有无障碍时依据控件树执行；什么都没有时提示词会把它限制在直达接口动作内，
 * 真正需要看屏/点屏的任务由它 finish 说明去「权限与环境」开启。
 */
@AndroidEntryPoint
class AssistantLauncherActivity : ComponentActivity() {

    @Inject lateinit var controller: AgentController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val instruction = intent?.getStringExtra(EXTRA_INSTRUCTION).orEmpty().trim()
        if (instruction.isNotBlank()) controller.start(instruction)
        finish()
    }

    companion object {
        const val EXTRA_INSTRUCTION = "instruction"
    }
}
