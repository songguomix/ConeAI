package com.cone.agent.remote

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Exported only for UU's result URL; the connector requires a persisted, single-use nonce. */
@AndroidEntryPoint
class UuRemoteCallbackActivity : ComponentActivity() {
    @Inject internal lateinit var connector: UuRemoteConnector
    private var pendingCallbacks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        consume(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        val raw = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        if (raw == null || raw.length > 20_000) {
            if (pendingCallbacks == 0) finish()
            return
        }
        pendingCallbacks++
        connector.acceptCallback(raw) {
            pendingCallbacks--
            // Do not finish while another onNewIntent result is still being saved.
            if (pendingCallbacks == 0 && !isFinishing) finish()
        }
    }
}
