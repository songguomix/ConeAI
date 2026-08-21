package com.cone.agent.assistant

import com.cone.agent.agent.ChatResponder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bridge for non-injectable components (the [android.service.voice.VoiceInteractionSession] is
 * created by the framework, not by Hilt). Fetch via
 * `EntryPointAccessors.fromApplication(context, AssistantEntryPoint::class.java)`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AssistantEntryPoint {
    fun chatResponder(): ChatResponder
}
