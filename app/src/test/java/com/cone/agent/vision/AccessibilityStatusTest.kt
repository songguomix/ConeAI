package com.cone.agent.vision

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityStatusTest {
    private fun TestScope.observe(enabled: Flow<Boolean>, connected: Flow<Boolean>): List<AccessibilityStatus> {
        val states = mutableListOf<AccessibilityStatus>()
        backgroundScope.launch { accessibilityStatus(enabled, connected).toList(states) }
        runCurrent()
        return states
    }

    @Test fun grantingAccessWaitsForTheServiceAndConnectingCancelsTheTimeout() = runTest {
        val enabled = MutableStateFlow(false)
        val connected = MutableStateFlow(false)
        val states = observe(enabled, connected)
        assertEquals(AccessibilityStatus.Disabled, states.last())

        enabled.value = true
        runCurrent()
        assertEquals(AccessibilityStatus.Connecting, states.last())
        advanceTimeBy(5_999)
        connected.value = true
        runCurrent()
        assertEquals(AccessibilityStatus.Connected, states.last())

        advanceTimeBy(6_000)
        runCurrent()
        assertFalse(AccessibilityStatus.Unbound in states)
        assertEquals(AccessibilityStatus.Connected, states.last())
    }

    @Test fun repeatedSettingNotificationsPreserveTheDeadlineAndALateBindRecovers() = runTest {
        // Settings observers may deliver the same value several times.
        val enabled = MutableSharedFlow<Boolean>(replay = 1)
        enabled.emit(true)
        val connected = MutableStateFlow(false)
        val states = observe(enabled, connected)
        assertEquals(AccessibilityStatus.Connecting, states.last())

        advanceTimeBy(4_000)
        enabled.emit(true)
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(AccessibilityStatus.Connecting, states.last())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AccessibilityStatus.Unbound, states.last())

        connected.value = true
        runCurrent()
        assertEquals(AccessibilityStatus.Connected, states.last())
    }

    @Test fun switchingOffCancelsTheWaitAndReenablingStartsANewDeadline() = runTest {
        val enabled = MutableStateFlow(true)
        val states = observe(enabled, MutableStateFlow(false))
        advanceTimeBy(4_000)
        enabled.value = false
        runCurrent()
        assertEquals(AccessibilityStatus.Disabled, states.last())
        advanceTimeBy(1_000)
        enabled.value = true
        runCurrent()
        advanceTimeBy(5_999)
        runCurrent()
        assertFalse(AccessibilityStatus.Unbound in states)
        assertEquals(AccessibilityStatus.Connecting, states.last())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(AccessibilityStatus.Unbound, states.last())
    }

    @Test fun aLiveConnectionWinsOverAStaleSettingAndRebindingClearsADisconnect() = runTest {
        val enabled = MutableStateFlow(false)
        val connected = MutableStateFlow(true)
        val states = observe(enabled, connected)
        assertEquals(AccessibilityStatus.Connected, states.last())
        enabled.value = true
        runCurrent()
        connected.value = false
        runCurrent()
        assertEquals(AccessibilityStatus.Connecting, states.last())
        advanceTimeBy(3_000)
        connected.value = true
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(AccessibilityStatus.Unbound in states)
        assertEquals(AccessibilityStatus.Connected, states.last())
    }
}
