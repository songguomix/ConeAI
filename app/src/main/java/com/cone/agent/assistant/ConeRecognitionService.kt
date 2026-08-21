package com.cone.agent.assistant

import android.content.Intent
import android.speech.RecognitionService

/**
 * Stub recognition service. A [android.service.voice.VoiceInteractionService] manifest entry must
 * name a recognition service, but Cone Agent drives speech-to-text through the platform default
 * recognizer (via [SpeechController]), so this implementation is intentionally empty.
 */
class ConeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
}
