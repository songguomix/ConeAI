package com.cone.agent.assistant

import android.service.voice.VoiceInteractionService

/**
 * Entry point that lets Cone Agent be selected as the system "Digital assistant app" and be summoned
 * by the assist gesture / long-press of the power or home button. The actual UI lives in
 * [ConeVoiceInteractionSession]; this service only declares the capability.
 */
class ConeVoiceInteractionService : VoiceInteractionService()
