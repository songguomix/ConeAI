package com.cone.agent.remote

import android.content.Context
import android.widget.TextView
import com.cone.agent.R
import com.cone.agent.core.LocaleHelper
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * Portrait QR scanner for pairing. zxing-android-embedded's default [CaptureActivity] is landscape;
 * this subclass is registered as `portrait` in the manifest and inflates our own styled layout
 * (`activity_portrait_scan` → warm-accent viewfinder + title/hint), so scanning happens upright with
 * a nicer UI. Wired via `ScanOptions.setCaptureActivity(...)`.
 *
 * Shared by both pairing flows — the ConeCode desktop remote and 配置手表端 — which is why the title
 * and hint are overridable through [EXTRA_TITLE] / [EXTRA_HINT] (string resource ids, passed with
 * `ScanOptions.addExtra`). They default to the ConeCode wording the layout carries.
 */
class PortraitCaptureActivity : CaptureActivity() {
    // Apply the app's chosen language (zh/en/ja) so the scanner title/hint aren't
    // stuck on the system locale — this Activity is outside Compose's AppLocale.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_portrait_scan)
        applyLabel(R.id.scan_title, EXTRA_TITLE)
        applyLabel(R.id.scan_hint, EXTRA_HINT)
        return findViewById(R.id.zxing_barcode_scanner)
    }

    private fun applyLabel(viewId: Int, extra: String) {
        val resId = intent?.getIntExtra(extra, 0) ?: 0
        if (resId != 0) findViewById<TextView>(viewId)?.setText(resId)
    }

    companion object {
        /** String resource id for the title across the top of the viewfinder. */
        const val EXTRA_TITLE = "cone_scan_title"

        /** String resource id for the hint below it. */
        const val EXTRA_HINT = "cone_scan_hint"
    }
}
