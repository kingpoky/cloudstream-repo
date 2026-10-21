package com.kingpoky.enxapi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keluarga "RPM" whitelabel (rpmvid/rpmlive/p2pplay, dll).
 *
 * Embed-nya `https://<host>/#<id>` — ID di FRAGMENT, bukan path, jadi
 * extractor bawaan CloudStream (yang mencocokkan host+path) melewatkannya.
 * Media asli diambil dari /api/v1/video, balasannya hex AES-CBC dengan
 * key/IV yang sudah dipakai NgeFilm (satu operator backend, banyak domain).
 *
 * Diverifikasi ffprobe 21/10/2026: anichin.rpmvid.com/#wn5fzz -> h264 720p.
 */
object RpmResolver {
    private const val KEY = "6b69656d7469656e6d75613931316361"
    private const val IV = "313233343536373839306f6975797472"

    suspend fun resolve(embed: String, callback: (ExtractorLink) -> Unit) {
        val host = embed.substringAfter("://").substringBefore("/")
        val id = embed.substringAfter("#", "").ifBlank { return }
        val json = try {
            decrypt(
                app.get(
                    "https://$host/api/v1/video?id=$id&w=1920&h=1080&r=enxapi.id",
                    headers = mapOf(
                        "Host" to host,
                        "Referer" to "https://$host/",
                        "Origin" to "https://$host",
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                    timeout = 30,
                ).text
            )
        } catch (e: Exception) {
            return
        }

        Regex(""""source"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.let { link ->
            callback(
                newExtractorLink(
                    "RPM Share", "RPM Share",
                    link.replace("\\/", "/"), ExtractorLinkType.M3U8
                ) { this.referer = "https://$host/" }
            )
        }
    }

    private fun decrypt(hexText: String): String {
        if (hexText.isEmpty() || hexText.startsWith("{")) return hexText
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(hex(KEY), "AES"),
            IvParameterSpec(hex(IV)),
        )
        val body = hexText.replace(Regex("[^0-9a-fA-F]"), "")
        return String(cipher.doFinal(hex(body)), Charsets.UTF_8)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
    }
}
