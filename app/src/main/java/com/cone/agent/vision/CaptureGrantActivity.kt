package com.cone.agent.vision

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Invisible one-shot MediaProjection consent: launched by the agent's `request_screen` action when
 * the **model itself decides** it needs to see the screen mid-task. On grant the capture service
 * starts and the agent's next observation carries a real screenshot; on refusal [denied] is set so
 * the waiting executor can tell the model not to ask again.
 */
@AndroidEntryPoint
class CaptureGrantActivity : ComponentActivity() {

    @Inject lateinit var captureManager: ScreenCaptureManager

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.start(this, result.resultCode, data)
            } else {
                denied = true
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (captureManager.isRunning) {
            finish()
            return
        }
        denied = false
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            denied = true
            finish()
            return
        }
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    companion object {
        /** True after the user dismissed/refused the latest consent dialog. */
        @Volatile
        var denied = false
    }
}
