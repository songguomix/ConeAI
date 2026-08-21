package com.cone.agent.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress

/**
 * Refuses to send credentials (API keys / remote passwords) over cleartext HTTP to *public*
 * addresses. Cleartext itself must stay enabled app-wide for local model servers (Ollama /
 * LM Studio / vLLM on localhost or a LAN IP) — Android's network-security-config cannot express
 * "any private address", so this interceptor enforces the actual invariant instead: a secret
 * never leaves the device unencrypted except toward the user's own local network.
 *
 * Registered as a *network* interceptor so the resolved socket address is known — the policy is
 * applied to where the bytes really go, not to what the hostname looks like. Runs before the
 * request body is transmitted.
 */
class CleartextCredentialGuard : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isHttps && request.hasCredentials()) {
            val address = chain.connection()?.socket()?.inetAddress
            if (address != null && !address.isPrivateOrLocal()) {
                throw IOException(
                    "已阻止：API Key/密码不能通过明文 http 发送到公网地址 ${request.url.host}，" +
                        "请将 Base URL 改为 https（本机/局域网地址不受影响）。",
                )
            }
        }
        return chain.proceed(request)
    }

    private fun okhttp3.Request.hasCredentials(): Boolean =
        header("Authorization") != null || header("x-api-key") != null || header("x-remote-pass") != null

    private fun InetAddress.isPrivateOrLocal(): Boolean {
        if (isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress) return true
        val bytes = address
        // 100.64.0.0/10 — CGNAT, also used by Tailscale-style private overlays.
        if (bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 0x40) return true
        // IPv6 ULA fc00::/7.
        if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }
}
