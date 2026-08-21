package com.cone.agent.core

/** App-wide constants shared across modules. */
object Constants {
    const val DB_NAME = "cone-agent.db"
    const val SECURE_PREFS = "cone_secure_prefs"
    const val SETTINGS_STORE = "cone_settings"

    // China-ROM runtime permission gating the installed-app list ("获取应用列表"). Undefined on stock
    // Android (where QUERY_ALL_PACKAGES covers us), so requesting it there is a harmless no-op.
    const val PERM_GET_INSTALLED_APPS = "com.android.permission.GET_INSTALLED_APPS"

    // Notification channels
    const val CHANNEL_AGENT = "cone_agent_channel"
    const val CHANNEL_CAPTURE = "cone_capture_channel"
    const val CHANNEL_WAKE = "cone_wake_channel"
    const val NOTIF_AGENT_ID = 1001
    const val NOTIF_CAPTURE_ID = 1002
    const val NOTIF_WAKE_ID = 1003

    // Agent loop limits
    const val DEFAULT_MAX_STEPS = 25
    // Sentinel stored when the user turns on "unlimited steps": the loop then runs until the task
    // finishes on its own. Displayed as "∞" and never triggers the max-steps stop.
    const val UNLIMITED_MAX_STEPS = Int.MAX_VALUE
    // Fallback wait after an action before re-observing; most actions use shorter, action-specific
    // waits (see AgentController.settleDelayFor). Lets the UI settle without over-waiting every step.
    // Kept generous: re-observing before the UI finishes transitioning causes misjudged actions.
    const val OBSERVE_SETTLE_MS = 500L

    // Batch mode: gap between two consecutive actions executed from the *same* screenshot, with no
    // re-observation in between. Small enough to feel fluid, large enough for each tap to register.
    const val SMOOTH_ACTION_GAP_MS = 160L

    // Adaptive settle: instead of always waiting the full per-action delay before re-observing, we
    // wait only until the screen has stopped transitioning (no window change for SCREEN_QUIET_MS),
    // polling every SCREEN_POLL_MS, capped by the per-action maximum. Fast screens re-observe sooner;
    // slow ones still get the full budget — so the loop speeds up without acting on a mid-transition.
    const val SCREEN_QUIET_MS = 140L
    const val SCREEN_POLL_MS = 35L

    // How many consecutive "think" failures (e.g. model returned no valid JSON) before giving up.
    const val MAX_CONSECUTIVE_FAILURES = 10

    // How long the agent status card lingers on a terminal result (succeeded/failed) on the main
    // screen before it auto-closes — long enough to read the outcome, short enough to get out of the
    // way. The completion summary also stays in the conversation log.
    const val AGENT_CARD_AUTO_DISMISS_MS = 4000L

    // Wake-word match leniency, 0f (strict: requires the literal 松果 — fewest false triggers) ..
    // 1f (loose: accepts many homophones — easiest to trigger). Mid is the balanced default.
    const val DEFAULT_WAKE_SENSITIVITY = 0.5f

    // Screenshot downscale target (longest edge) for model upload. Lower = faster upload + fewer
    // image tokens, but small UI text gets harder to read, which causes misrecognition. 1080 / q70
    // is the accuracy-first baseline; don't trim these to chase speed.
    const val SCREENSHOT_MAX_EDGE = 1080
    const val SCREENSHOT_JPEG_QUALITY = 70

    // Separate, much larger cap for the *local* OCR pass. The upload downscale above exists to save
    // image tokens — a budget on-device recognition doesn't share — and shrinking a 1080p screenshot
    // to a 498px-wide upload copy drops 12sp labels under ML Kit's ~16px-per-character floor (it asks
    // for 1024×768 or better), which matters far more now that OCR boxes drive real taps.
    // 2400 normalises any screen to the pixel density of a 1080×2340 phone: the common case is left
    // untouched at full resolution, and only QHD/4K panels are scaled down — so accuracy never drops
    // below the 1080p baseline while the worst-case cost stays bounded.
    const val OCR_MAX_EDGE = 2400
}
