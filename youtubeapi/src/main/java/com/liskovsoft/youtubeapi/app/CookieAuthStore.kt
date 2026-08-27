package com.liskovsoft.youtubeapi.app

import java.security.MessageDigest

/**
 * Browser-style InnerTube authorization.
 *
 * SmartTube signs in through the TV device-code flow, which yields an OAuth bearer token. That
 * token is only accepted for the TVHTML5 family, and googlevideo refuses the media urls those
 * clients mint. A browser stays playable because it authorizes as WEB using cookies plus a
 * SAPISIDHASH header, so mirror that here.
 *
 * See yuliskov/SmartTube#6030.
 */
object CookieAuthStore {
    private const val ORIGIN = "https://www.youtube.com"
    private const val AUTH_USER = "0"

    @Volatile
    private var cookieHeader: String? = null

    @JvmStatic
    fun setCookies(cookies: String?) {
        cookieHeader = cookies?.trim()?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun getCookies(): String? = cookieHeader

    @JvmStatic
    fun isEnabled(): Boolean = sapisid() != null

    /** Headers a signed-in browser sends on every InnerTube call. */
    @JvmStatic
    fun authHeaders(): Map<String, String> {
        val sapisid = sapisid() ?: return emptyMap()
        val cookies = cookieHeader ?: return emptyMap()
        val timestamp = System.currentTimeMillis() / 1000
        val digest = sha1("$timestamp $sapisid $ORIGIN")

        return mapOf(
            "Authorization" to "SAPISIDHASH ${timestamp}_$digest",
            "Cookie" to cookies,
            "X-Goog-AuthUser" to AUTH_USER,
            "Origin" to ORIGIN,
            "Referer" to "$ORIGIN/"
        )
    }

    private fun sapisid(): String? {
        val cookies = cookieHeader ?: return null

        for (part in cookies.split(";")) {
            val pair = part.trim()
            for (name in arrayOf("SAPISID=", "__Secure-3PAPISID=")) {
                if (pair.startsWith(name)) {
                    return pair.substring(name.length).takeIf { it.isNotEmpty() }
                }
            }
        }

        return null
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
