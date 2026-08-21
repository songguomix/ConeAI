package com.cone.agent.agent

import android.content.Context
import com.cone.agent.R
import com.cone.agent.agent.action.ActionType
import com.cone.agent.agent.action.PlannedAction
import com.cone.agent.core.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects high-risk steps (payments, transfers, deletions, sending messages, etc.) that must be
 * explicitly confirmed by the user before execution.
 *
 * Two signals, deliberately weighted differently:
 *  - **Evidence** — the tapped control's label, the text being typed, the app being opened. These
 *    come from the device, not from the model, so the model cannot talk its way past them.
 *  - **Prose** — the model's own thought/summary. Self-reported, so it may only *add* confirmations
 *    (for the specific irreversible operations), never stand in for evidence: matching generic verbs
 *    like 确认/提交 in the narration would fire on nearly every screen and train the user to
 *    rubber-stamp the dialog, while a model that simply omits the word would slip through anyway.
 */
@Singleton
class RiskGuard @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Returns a human-readable risk reason if the step is high-risk, otherwise null. */
    fun assess(action: PlannedAction, targetLabel: String?): String? {
        // Pure observation / navigation steps are always safe.
        if (action.type in SAFE_TYPES) return null

        val evidence = buildString {
            append(targetLabel.orEmpty()).append(' ')
            append(action.text.orEmpty()).append(' ')
            append(action.app.orEmpty())
        }.lowercase()

        val prose = buildString {
            append(action.thought).append(' ')
            append(action.summary)
        }.lowercase()

        val matched = IRREVERSIBLE.firstOrNull { evidence.containsKeyword(it) || prose.containsKeyword(it) }
            ?: GENERIC_ACTIONS.firstOrNull { evidence.containsKeyword(it) }
        return matched?.let { LocaleHelper.string(context, R.string.risk_reason, it) }
    }

    /** CJK keywords match as substrings; ASCII ones only on word boundaries ("send" ≠ "sender"). */
    private fun String.containsKeyword(keyword: String): Boolean =
        if (keyword.any { it.code > 0x7F }) {
            contains(keyword)
        } else {
            Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(this)
        }

    private companion object {
        /** Money / account-destroying operations: confirmed whether seen in evidence OR prose. */
        val IRREVERSIBLE = listOf(
            // 资金相关
            "付款", "支付", "确认支付", "转账", "汇款", "打款", "红包", "提现", "充值",
            "购买", "下单", "提交订单", "立即购买", "确认收货", "借款", "贷款", "还款",
            // 账号 / 数据
            "删除", "卸载", "格式化", "清除数据", "清空", "注销", "解绑", "退出登录",
            "修改密码", "重置密码", "实名认证", "授权登录",
            // English
            "pay", "payment", "transfer", "checkout", "purchase", "delete", "uninstall",
            "format", "logout", "reset password", "change password",
        )

        /** Generic verbs (发送/提交/确认…): only count when the *control itself* says so. */
        val GENERIC_ACTIONS = listOf(
            "发送", "发布", "转发", "提交", "确认",
            "send", "post", "submit", "confirm", "buy", "order",
        )

        val SAFE_TYPES = setOf(
            ActionType.CAPTURE_SCREEN,
            ActionType.WAIT,
            ActionType.BACK,
            ActionType.HOME,
            ActionType.RECENT_APPS,
            ActionType.SCROLL,
            ActionType.SWIPE,
            ActionType.FINISH,
            // Read-only lookups in the built-in browser — never mutate anything on the device.
            ActionType.WEB_SEARCH,
            ActionType.OPEN_URL,
            ActionType.OPEN_BROWSER,
            // Read-only free info fetches (weather / news / rates / crypto / stock / wiki / holiday).
            ActionType.WEATHER,
            ActionType.NEWS,
            ActionType.EXCHANGE_RATE,
            ActionType.CRYPTO,
            ActionType.STOCK,
            ActionType.WIKI,
            ActionType.HOLIDAY,
            ActionType.ROUTE,
            // 只是弹系统授权弹窗，最终裁决权在用户手里。
            ActionType.REQUEST_SCREEN,
            ActionType.MUSIC,
            ActionType.LYRICS,
            ActionType.IP_INFO,
            ActionType.WORLD_TIME,
            ActionType.AIR_QUALITY,
            ActionType.BOOK,
            ActionType.DICT,
            ActionType.GOLD,
            // Firing a map app's navigation URI is reversible (the user can just close the map) and
            // commits nothing; keyword-matching the destination text would only cause false alarms.
            ActionType.NAVIGATE,
            // Starting playback / opening a shop's search-results list commits nothing either; the
            // actual in-app 购买/下单 taps stay evidence-guarded like any other tap.
            ActionType.PLAY_MUSIC,
            ActionType.SHOPPING,
            // Composer/dialer prefills（拨出/发送那一下仍在用户或证据守卫手里）and freely reversible
            // device toggles (alarm / settings page / torch).
            ActionType.CALL,
            ActionType.SMS,
            ActionType.EMAIL,
            ActionType.ALARM,
            ActionType.OPEN_SETTINGS,
            ActionType.FLASHLIGHT,
            // The share intent only opens the system share sheet / target app's picker — nothing is
            // sent yet. The actual in-app 发送 tap is still evidence-guarded like any other tap, and
            // treating share's text payload as evidence would false-alarm on arbitrary message words.
            ActionType.SHARE,
        )
    }
}
