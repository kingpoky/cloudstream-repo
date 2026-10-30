package com.kingpoky.klikxxi

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * AsiaStream — pemutar film NodrakorId (watch.asiastream.cc).
 *
 * KENAPA EXTRACTOR SENDIRI, bukan loadExtractor() bawaan:
 * APK resmi punya `com.lagradost.cloudstream3.extractors.StreamEmbed`
 * (classes7.dex, mainUrl=https://watch.gxplayer.xyz) dengan formula URL yang
 * SAMA, tapi host `watch.asiastream.cc` tidak terdaftar sebagai alias di
 * extractor manapun — 0 hit di 8 dex. Akibatnya loadExtractor() melewatinya
 * DIAM-DIAM dan film NodrakorId tampil "tidak ada server" di HP meski
 * rantainya sehat dari sisi server.
 *
 * KENAPA TIDAK CUKUP SUBCLASS StreamEmbed:
 * StreamEmbed membaca `var video = {...};` lalu parseJson ke Details
 * (id/uid/slug/title/quality/type/status/md5). Halaman asiastream TIDAK
 * memuat `var video` sama sekali (diukur 30/10/2026: var_video=0, sniff=1);
 * datanya datang sebagai argumen posisional panggilan inline:
 *
 *   sniff("<id>","<uid>","<md5>",<poster>,<tracks>,<status>,...)
 *
 * Formula URL-nya tetap meniru StreamEmbed APA ADANYA:
 *   "/m3u8/" + uid + "/" + md5 + "/master.txt?s=1&id=" + id + "&cache=" + status
 *
 * GERBANG: tidak ada. Diuji 30/10/2026 — okhttp/4.12.0 tanpa Referer, tanpa
 * header apa pun, dan browser+Referer semuanya balas HTTP 200 (749 B master).
 * Jangan menambahkan header yang tidak dipakai klien asli.
 *
 * master.txt adalah HLS multi-varian (1280x720 @1.5Mbps, 854x480 @750kbps)
 * yang menunjuk /stream/<base64>, jadi serahkan ke pemutar sebagai M3U8 —
 * biarkan ExoPlayer yang memilih varian, jangan pilih sendiri di sini.
 */
class AsiaStreamExtractor : ExtractorApi() {
    override var name = "AsiaStream"
    override var mainUrl = "https://watch.asiastream.cc"
    override val requiresReferer = false

    // sniff("id","uid","md5",<poster>,<tracks>,<status>
    // poster boleh null/string; tracks boleh null atau array JSON.
    private val sniffRegex = Regex(
        """sniff\("([^"]*)","([^"]*)","([^"]*)",[^,]*,(?:\[[^\]]*]|null),([^,)]*)"""
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = app.get(url, referer = referer, timeout = 30).text
        val m = sniffRegex.find(page) ?: return
        val (id, uid, md5, status) = m.destructured
        val cache = status.trim().trim('\'', '"')
        if (uid.isBlank() || md5.isBlank()) return

        val master = "$mainUrl/m3u8/$uid/$md5/master.txt?s=1&id=$id&cache=$cache"

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = master,
                type = ExtractorLinkType.M3U8
            ) {
                // Referer host-sendiri: aman walau origin tidak memeriksanya,
                // dan tetap benar kalau suatu hari gerbang dipasang.
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
