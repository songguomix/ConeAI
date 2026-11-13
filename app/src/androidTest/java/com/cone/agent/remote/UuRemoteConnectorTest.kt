package com.cone.agent.remote

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cone.agent.data.crypto.KeystoreManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore and SharedPreferences; no UU login or live device is impersonated. */
@RunWith(AndroidJUnit4::class)
class UuRemoteConnectorTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("instrumentation_$name", mode)
    }
    private val preferences = context.getSharedPreferences("uu_remote", Context.MODE_PRIVATE)
    private val keystore = KeystoreManager()
    private val nonce = "instrumentation-nonce"

    private fun seed(kind: String = "BIND", currentDevice: String? = null, pendingDevice: String? = null) {
        val request = JSONObject().put("nonce", nonce).put("computerId", "computer-b").put("kind", kind)
            .put("createdAt", System.currentTimeMillis())
        pendingDevice?.let { request.put("deviceId", it) }
        val devices = JSONObject().put("computer-a", "other-computer-device")
        currentDevice?.let { devices.put("computer-b", it) }
        val json = JSONObject().put("devices", devices).put("pending", JSONArray().put(request)).toString()
        val sealed = keystore.encrypt(json)
        assertTrue("Android Keystore encryption must work", sealed.cipherText.isNotBlank())
        assertTrue(preferences.edit().clear().putString("cipher", sealed.cipherText).putString("iv", sealed.iv).commit())
    }

    private suspend fun connector(): UuRemoteConnector = UuRemoteConnector(context, keystore).also { connector ->
        withTimeout(5_000) { connector.state.first { !it.loading } }
    }

    @After fun cleanup() { preferences.edit().clear().commit() }

    @Test fun bindingIsEncryptedScopedPersistentAndSingleUse() = runBlocking {
        seed()
        val connector = connector()
        assertFalse(connector.callback("coneai-uu://bind?nonce=wrong&anonymous_device_id=forged"))
        assertTrue(connector.callback("coneai-uu://bind?nonce=$nonce&anonymous_device_id=opaque%2B%2F%3D"))
        assertEquals("opaque+/=", connector.state.value.devices["computer-b"])
        assertEquals("other-computer-device", connector.state.value.devices["computer-a"])
        assertFalse(preferences.getString("cipher", "")!!.contains("opaque"))
        val recreated = connector()
        assertEquals("opaque+/=", recreated.state.value.devices["computer-b"])
        assertFalse(recreated.callback("coneai-uu://bind?nonce=$nonce&anonymous_device_id=replayed"))
        assertEquals("opaque+/=", recreated.state.value.devices["computer-b"])
    }

    @Test fun deviceNotFoundInvalidatesOnlyItsCurrentBinding() = runBlocking {
        seed("CONTROL", "current-id", "current-id")
        val connector = connector()
        assertTrue(connector.callback("coneai-uu://control?request_id=$nonce&status=failure&reason=device_not_found"))
        assertNull(connector.state.value.devices["computer-b"])
        assertEquals("other-computer-device", connector.state.value.devices["computer-a"])
        assertEquals("device_not_found", connector.state.value.events["computer-b"]?.reason)
    }

    @Test fun staleDeviceFailureCannotDeleteANewerBinding() = runBlocking {
        seed("CONTROL", "new-device", "old-device")
        val connector = connector()
        assertTrue(connector.callback("coneai-uu://control?request_id=$nonce&status=failure&reason=device_not_found"))
        assertEquals("new-device", connector.state.value.devices["computer-b"])
    }

    @Test fun pipReadyRequiresAMatchingPendingControlAndDoesNotChangeBinding() = runBlocking {
        seed("CONTROL", "current-id", "current-id")
        val connector = connector()
        assertFalse(connector.callback("coneai-uu://control?request_id=wrong&status=success"))
        assertTrue(connector.callback("coneai-uu://control?request_id=$nonce&status=success"))
        assertEquals(UuStage.PIP_READY, connector.state.value.events["computer-b"]?.stage)
        assertEquals("current-id", connector.state.value.devices["computer-b"])
        assertFalse(connector.callback("coneai-uu://control?request_id=$nonce&status=success"))
    }
    @Test fun overlappingCallbacksConsumeTheNonceOnce() = runBlocking {
        seed()
        val connector = connector()
        val results = listOf("first", "second").map { id ->
            async(Dispatchers.Default) { connector.callback("coneai-uu://bind?nonce=$nonce&anonymous_device_id=$id") }
        }.awaitAll()
        assertEquals(1, results.count { it })
        assertTrue(connector.state.value.devices["computer-b"] in setOf("first", "second"))
        assertFalse(connector.callback("coneai-uu://bind?nonce=$nonce&anonymous_device_id=replay"))
    }

}
