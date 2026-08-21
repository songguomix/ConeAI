package com.cone.agent.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** One local-OCR pass: the flat text plus every recognised line as an addressable element. */
data class OcrResult(
    val text: String,
    val elements: List<OcrElement>,
) {
    companion object {
        val EMPTY = OcrResult("", emptyList())
    }
}

/** ML Kit on-device OCR. Uses the Chinese recogniser, which also covers Latin scripts. */
@Singleton
class OcrEngine @Inject constructor() {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    )

    /** Flat text only (kept for callers that don't need coordinates). */
    suspend fun recognize(bitmap: Bitmap): String = recognizeStructured(bitmap).text

    /**
     * Recognises the screen and returns every line as an [OcrElement] with its own id, bounding box
     * and centre point, in **[bitmap]'s pixel space** — so pass the full-resolution frame and the
     * boxes come back as real screen pixels. Feeding a downscaled copy costs accuracy: ML Kit asks
     * for at least 1024×768, and small labels fall under its ~16px-per-character floor once shrunk.
     *
     * Line granularity is what the agent needs: a text *block* merges a whole paragraph into one box
     * that's useless as a tap target, while a single *word* splits multi-word labels apart. Elements
     * come back in reading order (top to bottom, then left to right) and are numbered in that order,
     * so `ocr_1` is the first thing on screen no matter what order ML Kit found them in.
     */
    suspend fun recognizeStructured(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (!cont.isActive) return@addOnSuccessListener
                val boxes = result.textBlocks
                    .asSequence()
                    .flatMap { it.lines.asSequence() }
                    .mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        val text = line.text.trim()
                        if (text.isBlank() || box.width() <= 0 || box.height() <= 0) null else text to box
                    }
                    .sortedWith(compareBy({ it.second.top }, { it.second.left }))
                    .toList()
                val elements = boxes.mapIndexed { index, (text, box) ->
                    OcrElement(id = "ocr_${index + 1}", text = text, bounds = Rect(box))
                }
                cont.resume(OcrResult(result.text, elements))
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(OcrResult.EMPTY)
            }
    }
}
