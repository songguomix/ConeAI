package com.cone.agent.vision

import android.graphics.Rect

/** A single interactive node extracted from the Accessibility UI tree. */
data class UiElement(
    val text: String,
    val contentDescription: String?,
    val className: String?,
    val resourceId: String?,
    val packageName: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    val label: String
        get() = text.ifBlank { contentDescription ?: "" }

    /**
     * A *weak* label for the icon-only controls that make up most of [label]'s blanks. Developers
     * name their views after what they do — `ic_back`, `btn_search`, `iv_more`, `fab_add` — and that
     * id already rides along in the tree at zero cost, so a bare icon that would otherwise reach the
     * model as an anonymous tappable box can announce its purpose. When the id says nothing useful,
     * falls back to the widget kind (Switch / EditText / ImageButton), which at least tells the
     * model what *sort* of control it is. Blank when neither yields anything.
     *
     * Deliberately a hint, not a label: ids are developer shorthand, may be stale, abbreviated or
     * simply wrong, so the prompt marks it as lower-confidence than real text.
     */
    val semanticHint: String
        get() {
            val fromId = resourceId?.substringAfterLast('/')?.let(::humanizeId).orEmpty()
            if (fromId.isNotBlank()) return fromId
            return className?.substringAfterLast('.')?.takeIf { it in INFORMATIVE_WIDGETS }.orEmpty()
        }

    private companion object {
        /** Splits `searchButton` / `search_button` alike, so both id styles humanise the same way. */
        val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

        /**
         * Structural and type noise every Android id is padded with. Only *type* words are dropped —
         * direction words (left/up/forward) stay, because on an arrow icon they carry the meaning.
         */
        val ID_NOISE = setOf(
            "ic", "icon", "btn", "bt", "button", "ib", "iv", "img", "image", "tv", "txt", "text",
            "et", "ll", "rl", "fl", "cl", "rv", "vp", "fab", "view", "layout", "container", "root",
            "parent", "wrapper", "content", "cell", "holder", "group", "panel", "box", "area",
            "item", "id", "default", "normal", "widget", "res", "android", "com", "www",
            "action", "fragment", "activity", "frag", "recycler",
        )

        /** Widget classes worth naming when the id gave us nothing; the rest are layout plumbing. */
        val INFORMATIVE_WIDGETS = setOf(
            "ImageButton", "ImageView", "Switch", "SwitchCompat", "CheckBox", "RadioButton",
            "SeekBar", "EditText", "Button", "ToggleButton", "RatingBar", "Spinner", "WebView",
            "VideoView",
        )

        const val MAX_HINT_WORDS = 4
        const val MAX_HINT_CHARS = 24
        val DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')

        /** `btn_searchBar_2` → `search bar`. Empty when nothing but noise survives. */
        fun humanizeId(raw: String): String = raw
            .replace(CAMEL_BOUNDARY, "$1 $2")
            .split('_', ' ', '-')
            .asSequence()
            // Trailing counters are pure noise and they hide the noise word underneath:
            // `button1` only reads as boilerplate once the digit is off.
            .map { it.lowercase().trimEnd(*DIGITS) }
            .filter { it.length > 1 && it !in ID_NOISE }
            .take(MAX_HINT_WORDS)
            .joinToString(" ")
            .take(MAX_HINT_CHARS)
    }
}

/**
 * A single line of text found by the local OCR pass, addressable by [id] (`ocr_1`, `ocr_2`, …).
 * The bounds are in the pixel space of the analysed frame; by the time one reaches a
 * [ScreenSnapshot] any OCR-side downscale has been undone, so they are **real screen pixels**,
 * exactly like [UiElement.bounds].
 */
data class OcrElement(
    val id: String,
    val text: String,
    val bounds: Rect,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    /** The same element with its box multiplied by [factor] (OCR frame → real screen pixels). */
    fun scaledBy(factor: Float): OcrElement = if (factor == 1f) {
        this
    } else {
        copy(
            bounds = Rect(
                (bounds.left * factor).toInt(),
                (bounds.top * factor).toInt(),
                (bounds.right * factor).toInt(),
                (bounds.bottom * factor).toInt(),
            ),
        )
    }
}

/** Everything the agent "sees" in one observation cycle. */
data class ScreenSnapshot(
    /** Width/height of the image **as sent to the model** (downscaled image space). */
    val width: Int,
    val height: Int,
    /**
     * Factor mapping model/image-space coordinates back to real screen pixels:
     * `realPixel = modelPixel * scale`. 1f when the screenshot was not downscaled.
     */
    val scale: Float = 1f,
    val ocrText: String,
    /**
     * The same OCR pass as [ocrText], but line by line with ids and boxes in real screen pixels —
     * this is what the model reasons over, so it can read text the accessibility tree never exposes
     * (WebViews, canvases, images, games) and still get a coordinate to tap.
     */
    val ocrElements: List<OcrElement> = emptyList(),
    val uiElements: List<UiElement>,
    val currentPackage: String?,
    /**
     * The downscaled screenshot, already JPEG/base64-encoded for upload. Null both when the capture
     * failed **and** when the mode deliberately uploads nothing (local OCR) — [hasFrame] tells the
     * two apart, which matters because only the first means the agent is actually screen-blind.
     */
    val imageDataUrl: String? = null,
    /** Whether a real screen frame was captured this cycle, regardless of whether it was uploaded. */
    val hasFrame: Boolean = false,
    /**
     * Whether the accessibility service is connected — i.e. whether taps, text input and swipes can
     * actually reach the screen. Distinct from having *seen* the screen: capture and accessibility
     * are granted separately, and with only the former the agent can read everything and touch
     * nothing, every gesture failing silently.
     */
    val canAct: Boolean = false,
    /**
     * The accessibility screen-change counter sampled at observe time. If it differs while a batch
     * of actions from this snapshot is still running, the screen changed and the agent re-observes.
     */
    val screenChangeToken: Long = 0L,
)
