package com.kingpoky.enxapi

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

/**
 * Pembuka token `stream/proxy?t=<token>` milik jalur anime enxapi.
 *
 * Token itu BUKAN JWT bertanda tangan — tidak ada signature yang diperiksa
 * sisi kita, dan segmen pertamanya JSON polos ter-base64url:
 *
 *     {"u":"https://edge02.upbolt.to/hls2/.../index.m3u8",
 *      "h":{"Origin":"https://upbolt.to","Referer":"https://upbolt.to/e/<id>"},
 *      "e":1792571224}
 *
 * Membukanya memberi dua hal yang tidak ada di luar: URL CDN asli, dan
 * header persis yang dituntut CDN itu. Memutar langsung dari CDN berarti
 * byte tidak menumpang server enxapi sama sekali.
 *
 * Sengaja TIDAK melempar: pemanggil memakai URL proxy sebagai cadangan
 * kalau format token berubah, karena jalur proxy juga terbukti memutar.
 */
object ProxyToken {
    data class Unwrapped(val url: String, val headers: Map<String, String>?)

    fun unwrap(raw: String): Unwrapped? {
        val token = raw.substringAfter("t=", "").substringBefore("&")
        if (token.isEmpty()) return null
        val payload = token.substringBefore(".")
        val json = try {
            String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING))
        } catch (e: Exception) {
            return null
        }
        val parsed = tryParseJson<TokenPayload>(json) ?: return null
        val url = parsed.u?.takeIf { it.startsWith("http") } ?: return null
        return Unwrapped(url, parsed.h?.takeIf { it.isNotEmpty() })
    }

    private data class TokenPayload(
        @JsonProperty("u") val u: String? = null,
        @JsonProperty("h") val h: Map<String, String>? = null,
    )
}
