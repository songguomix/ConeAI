package com.cone.agent.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Hands the system a fresh [ConeVoiceInteractionSession] each time the assistant is summoned. */
class ConeVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        ConeVoiceInteractionSession(this)
}
