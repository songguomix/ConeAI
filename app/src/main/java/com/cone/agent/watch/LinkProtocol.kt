package com.cone.agent.watch

import android.util.Base64
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Watch ⇄ phone pairing protocol ("手表端配置").
 *
 * The watch shows a QR; the phone scans it and then pushes its provider configuration over the
 * LAN. Only one direction of information is carried by the QR, so the watch — not the phone —
 * chooses the Wi-Fi credentials: it names a hotspot SSID/passphrase, pre-registers a Wi-Fi
 * suggestion for it, and the phone simply walks the user through switching that hotspot on. From
 * there the two find each other and finish without any further tapping.
 *
 * This file mirrors `com.cone.agent.link.LinkProtocol` in the watch project — the two apps are
 * separate Gradle builds with no shared module, and a short protocol is cheaper to mirror than to
 * extract into a published artifact. Change one, change the other.
 */
object LinkConstants {
    /** TCP port of the watch's config server. */
    const val PORT = 8724

    /** UDP port the watch announces itself on, so the phone can skip the subnet sweep. */
    const val ANNOUNCE_PORT = 8725

    /** Prefix of both the QR payload and the UDP announcement — a version marker, so a future
     *  protocol change is rejected cleanly instead of half-parsed. */
    const val MAGIC = "CONEW1"

    const val PATH_HELLO = "/cone/hello"
    const val PATH_CONFIG = "/cone/config"

    /** Header carrying the pairing token on a config push. */
    const val HEADER_TOKEN = "x-cone-token"
}

/**
 * What the QR encodes: `CONEW1|<id>|<password>|<token>|<port>`.
 *
 * Kept to ~56 characters on purpose. A watch renders the QR at ~170dp; at that size a longer
 * payload pushes the symbol past version 6 and the modules get too fine for a phone camera to
 * resolve across the glass of a curved display.
 */
data class PairPayload(
    val id: String,
    val password: String,
    val token: String,
    val port: Int = LinkConstants.PORT,
) {
    /** The hotspot name the watch will look for; derived from [id] so it needn't be in the QR. */
    val ssid: String get() = ssidFor(id)

    fun encode(): String = listOf(LinkConstants.MAGIC, id, password, token, port.toString()).joinToString("|")

    companion object {
        fun ssidFor(id: String): String = "ConeAI-" + id.take(4).uppercase()

        fun parse(raw: String?): PairPayload? {
            val parts = raw?.trim()?.split("|") ?: return null
            if (parts.size < 5 || parts[0] != LinkConstants.MAGIC) return null
            val id = parts[1].takeIf { it.isNotBlank() } ?: return null
            val password = parts[2].takeIf { it.length >= 8 } ?: return null
            val token = parts[3].takeIf { it.isNotBlank() } ?: return null
            val port = parts[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return PairPayload(id, password, token, port)
        }
    }
}

/** `GET /cone/hello` — unauthenticated, so the phone can tell "a watch" from "some other host". */
@Serializable
data class WatchHello(
    val app: String = "coneai-watch",
    val v: Int = 1,
    val id: String,
    val name: String,
    val paired: Boolean = false,
)

/** One provider row copied from the phone, API key included. */
@Serializable
data class PushProvider(
    val name: String,
    val baseUrl: String,
    val protocol: String = "openai",
    val apiKey: String = "",
    val models: List<String> = emptyList(),
)

/** Which model the watch should use for 问答, named by provider *name* (ids differ per device). */
@Serializable
data class PushSelection(val providerName: String, val modelId: String)

/**
 * The watch's speech settings, filled in on the phone.
 *
 * These live on the watch but are unenterable there — picking a transcription model means typing
 * something like `gpt-4o-mini-transcribe` on a 40mm screen. Providers are named rather than
 * referenced by id, same as [PushSelection], and the credentials still come from the provider row
 * the name resolves to, so no key is duplicated here.
 *
 * Engine/transport values are the `wireName`s of the watch's own enums; anything unrecognised falls
 * back to the on-device engine, which is what those enums already do with unknown input.
 */
@Serializable
data class PushSpeech(
    val ttsEngine: String = "device",
    val ttsProviderName: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    val asrEngine: String = "device",
    val asrProviderName: String = "",
    val asrModel: String = "",
    val asrLanguage: String = "",
    val asrTransport: String = "chat",
)

/** Body of `POST /cone/config`, sealed before it goes on the wire. */
@Serializable
data class WatchConfigPush(
    val v: Int = 1,
    val providers: List<PushProvider> = emptyList(),
    val chat: PushSelection? = null,
    val speech: PushSpeech? = null,
    val language: String? = null,
    val searchEngine: String? = null,
)

/** Reply to a config push. */
@Serializable
data class PushResult(
    val ok: Boolean,
    val providers: Int = 0,
    val models: Int = 0,
    val error: String? = null,
)

/** AES-GCM envelope, both fields Base64 (NO_WRAP). */
@Serializable
data class SealedEnvelope(val iv: String, val data: String)

/**
 * Seals the config payload with a key derived from the pairing token.
 *
 * The push carries API keys, and the transport is plain HTTP on a LAN that may be a shared Wi-Fi
 * rather than the phone's own hotspot. The token only ever existed on the watch screen and in the
 * scanning phone's camera, so anything else on the network sees ciphertext.
 */
object LinkCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun keyOf(token: String) =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)), "AES")

    fun seal(token: String, plain: String): SealedEnvelope {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyOf(token), GCMParameterSpec(TAG_BITS, iv))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return SealedEnvelope(
            iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            data = Base64.encodeToString(out, Base64.NO_WRAP),
        )
    }

    /** Returns null when the token is wrong or the payload was tampered with (GCM tag mismatch). */
    fun open(token: String, envelope: SealedEnvelope): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyOf(token),
            GCMParameterSpec(TAG_BITS, Base64.decode(envelope.iv, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(envelope.data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
}
