package com.kingpoky.pencuri

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
 * Pencuri (PencuriMovie) — tema "DooPlay" (div.ml-item/div.movieplay),
 * BEDA dari engine Kazefuri maupun KlikxxiEngine yang sudah ditulis.
 * RE dari com.lagradost.cloudxtream Pencuri.smali (key "co4" -> pencurimovie.baby).
 * Domain diverifikasi live 20/10/2026: 2 sample (The Avengers, Heist Dil Adha).
 * TEMUAN PENTING: meta[itemprop=embedUrl] CUMA trailer YouTube (pola sama kasus
 * Gomov yg ternyata trailer-only) - server player ASLI ada di iframe[data-src]
 * (doodstream.com/ahvsh.com/sblona.com/uptostream.com), BUKAN meta embedUrl.
 * Semua server (multi-tab "Web-dl") sudah ada di 1 response HTML, TIDAK perlu
 * AJAX/fetch per-tab terpisah (beda dari pola Kazefuri/Klikxxi yg butuh re-fetch
 * tiap tab) - lebih simpel & cepat.
 */
class Pencuri : MainAPI() {
    override var mainUrl = "https://ww44.pencurimovie.baby"
    override var name = "PencuriMovie"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "movies/page/%d/" to "Movie Terbaru",
        "series/page/%d/" to "Series Terbaru",
        "country/indonesia/page/%d/" to "Dari Indonesia",
        "country/japan/page/%d/" to "Dari Jepang",
        "country/india/page/%d/" to "Dari India",
        "country/thailand/page/%d/" to "Dari Thailand",
        "country/malaysia/page/%d/" to "Dari Malaysia",
    )

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleEl = element.selectFirst("span.mli-info h2") ?: return null
        val title = titleEl.text().trim()
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val poster = element.selectFirst("img")?.let { getImageAttr(it) }
        val isTv = href.contains("/series/")
        return newMovieSearchResponse(title, href, if (isTv) TvType.TvSeries else TvType.Movie) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.ml-item").mapNotNull { toSearchResult(it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.replace("%d", page.toString())
        val doc = app.get("$mainUrl/$path").document
        val items = doc.select("div.ml-item").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h3[itemprop=name]")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("img[itemprop=image]")?.let { getImageAttr(it) }
        val synopsis = doc.selectFirst("div.desc p.f-desc")?.text()?.trim()
        val genres = doc.select("div.mvici-left p:contains(Genre:) > a").map { it.text() }

        val episodeLinks = doc.select("div.tvseason div.les-content a")
        val isTvSeries = url.contains("/series/") && episodeLinks.isNotEmpty()

        return if (isTvSeries) {
            val episodes = episodeLinks.mapNotNull { a ->
                val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                // Tab episode pakai anchor "#tabN" internal, bukan URL terpisah -
                // data video tetap diambil dari halaman utama (loadLinks re-fetch url yg sama).
                newEpisode(url) { this.episode = epNum ?: 1 }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = synopsis
                tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = synopsis
                tags = genres
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // SEMUA server (multi-tab "Web-dl") sudah ada di 1 HTML response ini,
        // ambil iframe[data-src] (player asli) - BUKAN meta[itemprop=embedUrl]
        // yang cuma trailer YouTube (live-check 20/10/2026).
        val iframes = doc.select("div.movieplay iframe[data-src], div.tvseason iframe[data-src]")

        if (iframes.isEmpty()) return false

        iframes.forEach { iframe ->
            val src = iframe.attr("data-src")
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), mainUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}
