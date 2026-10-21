package com.kingpoky.dubbindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

// Util lokal (BUKAN API built-in CloudStream).
fun getImageAttr(el: Element): String? {
    var url = el.attr("data-src").ifEmpty { el.attr("src") }
    if (url.isEmpty()) {
        val srcset = el.attr("srcset")
        if (srcset.isNotEmpty()) {
            url = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: ""
        }
    }
    return url.ifEmpty { null }?.let { httpsify(it) }
}

/**
 * Dubbindo — video self-hosted (BUKAN iframe embed pihak ketiga seperti
 * provider lain). RE dari com.lagradost.cloudxtream Dubbindo.smali, TAPI
 * implementasi loadLinks DITULIS ULANG lebih sederhana+robust dari smali asli:
 * smali pakai regex khusus 1 pola URL (files.dubbindo.site/.../_converted.mp4
 * dari <script> tag) yang TERBUKTI basi saat live-check 20/10/2026 - situs
 * sekarang punya 2 pola URL beda tergantung umur konten:
 *   - konten lama: stream.dubbindo.site/uvideoweb/movie/<nama>.mp4 (tag <source> langsung)
 *   - konten baru: stream.dubbindo.site/upload/videos/..._video_720p_converted.mp4
 * Solusi robust: select tag <video source> langsung (universal, cover kedua pola,
 * tidak bergantung pada 1 format URL spesifik yang bisa berubah lagi).
 * Domain diverifikasi live 20/10/2026: search 20 hasil, 2 sample detail halaman
 * (Naruto Movie 1 & One Piece ep.2) dua-duanya punya <source> MP4 valid,
 * dicek HTTP Range request -> 206 Partial Content, magic bytes ftypmp42 asli.
 */
class Dubbindo : MainAPI() {
    override var mainUrl = "https://www.dubbindo.site"
    override var name = "DubbIndo"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)

    private val interceptor by lazy { CloudflareKiller() }

    private suspend fun request(url: String) =
        app.get(url, interceptor = interceptor, timeout = 30)

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleEl = element.selectFirst("h4, div.video-title") ?: return null
        val title = titleEl.text().trim()
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val poster = element.selectFirst("div.video-thumb a img, div.ra-thumb a img")
            ?.let { getImageAttr(it) }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = request("$mainUrl/search?keyword=$query").document
        return doc.select("div.videos-latest-list.row div.video-wrapper").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = request(url).document

        val title = doc.selectFirst("div.video-big-title h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val synopsis = doc.selectFirst("div.watch-video-description p")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
            posterUrl = poster
            plot = synopsis
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = request(data).document
        val sources = doc.select("video source[src]")

        if (sources.isEmpty()) return false

        sources.forEach { source ->
            val src = source.attr("src")
            if (src.isBlank()) return@forEach
            val qualityLabel = source.attr("data-quality").ifBlank { "HD" }
            val qualityNum = Regex("""(\d+)p?""").find(qualityLabel)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            callback(
                ExtractorLink(
                    source = this.name,
                    name = "${'$'}{this.name} $qualityLabel",
                    url = src,
                    referer = mainUrl,
                    quality = qualityNum,
                    type = ExtractorLinkType.VIDEO,
                )
            )
        }
        return true
    }
}
