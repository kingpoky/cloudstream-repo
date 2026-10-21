package com.kingpoky.klikxxi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

// Util lokal (BUKAN API built-in CloudStream), sama pola dengan family Kazefuri.
fun removeBloatx(text: String): String =
    text.replace(Regex("(?i)\\s*(sub indo|subtitle indonesia)\\s*"), "").trim()

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
 * KlikxxiEngine — engine dasar family "muvipro" WP-movie-theme (gmr-* classes).
 * Diturunkan via RE langsung dari com.lagradost.cloudxtream v4.9.43 (Klikxxi.smali,
 * method search$suspendImpl/load$suspendImpl/loadLinks$suspendImpl + 4 lambda
 * loadLinks$2..5), ditulis ulang RINGKAS -- bukan salinan 5313 baris aslinya,
 * cukup logic yang terbukti dipakai provider turunan (KawanFilm, NodrakorId).
 * Tidak mengimplementasi landingPageUrl/getRealBaseUrl krn domain anak yang
 * sudah dicek TIDAK redirect (final URL = mainUrl asli, beda dari KlikXXI
 * lama yang memang redirect klikxxi.club->klikxxi.shop dan sudah dicabut
 * dari katalog 21/10/2026 — 3 mirror-nya semua butuh browser penuh).
 */
open class KlikxxiEngine : MainAPI() {
    override var mainUrl = ""
    override var name = ""
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val interceptor by lazy { CloudflareKiller() }

    private val blacklistHosts = listOf(
        "short.icu", "hellabyte", "helvid.net", "filespayouts.com",
        // Widget iklan judi terverifikasi live di web.kawanfilm21.co 20/10/2026 -
        // defense-in-depth kalau host sama muncul di provider lain family ini.
        "cutt.ly", "866178.com", "refpa3665.com", "goratu.site", "menujupenta.site",
    )

    // Nama fetchDoc (bukan "request") SENGAJA dipakai: parameter getMainPage()
    // bernama "request: MainPageRequest" (signature wajib CloudStream) akan
    // shadow fungsi private kalau namanya sama - ketemu live saat nulis
    // NodrakorId, diperbaiki sebelum sempat salah compile.
    private suspend fun fetchDoc(url: String, ref: String? = null) =
        app.get(url, interceptor = interceptor, referer = ref, timeout = 30)

    open fun toSearchResult(element: Element): SearchResponse? {
        val titleA = element.selectFirst("h2.entry-title > a") ?: return null
        val rawTitle = removeBloatx(titleA.text())
        val title = Regex("""(\d{4})$""").replace(rawTitle, "($1)")
        val href = fixUrl(titleA.attr("href"))
        val poster = element.selectFirst("a > img, div.content-thumbnail img")
            ?.let { getImageAttr(it) }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query&post_type[]=post&post_type[]=tv"
        val doc = fetchDoc(url).document
        return doc.select("article.item").mapNotNull { toSearchResult(it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.replace("%d", page.toString())
        val pageUrl = if (path.startsWith("http")) path else "$mainUrl/$path"
        val doc = fetchDoc(pageUrl).document
        val items = doc.select("article[itemscope=itemscope], article.item").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchDoc(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.let { removeBloatx(it) } ?: ""
        val poster = doc.selectFirst("figure.pull-left > img")?.let { getImageAttr(it) }

        val yearText = doc.selectFirst(
            "span.gmr-movie-genre:contains(Year:) > a, div.gmr-moviedata:contains(Year:) > a"
        )?.text()
        val year = Regex("""(\d{4})$""").find(yearText ?: "")?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d{4})$""").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val genres = doc.select(
            "span.gmr-movie-genre:contains(Genre:) > a, div.gmr-moviedata:contains(Genre:) > a"
        ).map { it.text() }

        val synopsis = doc.selectFirst("div[itemprop=description] > p")?.text()?.trim()

        val isTvSeries = url.contains("/tv/")
        val episodeEls = doc.select("div.vid-episodes a, div.gmr-listseries a")

        return if (isTvSeries && episodeEls.isNotEmpty()) {
            val episodes = episodeEls.mapNotNull { a ->
                val epHref = a.attr("href")
                if (epHref.isBlank()) return@mapNotNull null
                val epNum = Regex("""Episode\s*([0-9]+)""", RegexOption.IGNORE_CASE)
                    .find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                newEpisode(fixUrl(epHref)) { this.episode = epNum }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                plot = synopsis
                tags = genres
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = synopsis
                tags = genres
            }
        }
    }

    private fun getIframeAttr(el: Element?): String? {
        el ?: return null
        val lazy = el.attr("data-litespeed-src")
        if (lazy.isNotBlank()) return lazy
        val src = el.attr("src")
        return src.ifBlank { null }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = fetchDoc(data).document
        val sources = mutableSetOf<String>()

        // Jalur 1: tab player (ul.muvipro-player-tabs) -> request tiap tab -> iframe gmr-embed.
        // Tab href live-check (NodrakorId 20/10/2026): tab-1 = URL penuh (halaman itu
        // sendiri), tab lain = path relatif "/judul/?player=N" - fixUrl() builtin
        // CloudStream sudah prepend mainUrl benar utk kedua bentuk, tidak perlu
        // percabangan manual.
        doc.select("ul.muvipro-player-tabs li a").forEach { tab ->
            val rawHref = tab.attr("href")
            if (rawHref.isBlank()) return@forEach
            val tabHref = fixUrl(rawHref)
            try {
                val tabDoc = if (tabHref == data) doc else fetchDoc(tabHref, data).document
                val iframe = tabDoc.selectFirst("div.gmr-embed-responsive iframe")
                val src = getIframeAttr(iframe)
                if (src != null) sources.add(fixUrl(src))
            } catch (_: Exception) { /* tab mati, lanjut tab lain */ }
        }

        // Jalur 2: daftar download langsung (div#download)
        doc.select("div#download > ul > li a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.isNotBlank()) sources.add(fixUrl(href))
        }

        // Jalur 3 (table > tbody > tr > td a) SENGAJA DIHAPUS: live-check di
        // web.kawanfilm21.co membuktikan selector ini SELALU match widget
        // iklan statis (cutt.ly/situs judi), bukan server movie dinamis -
        // beda dari asumsi awal baca smali. Download real sudah kebawa via
        // Jalur 2 (div#download) tanpa perlu jalur ini.

        // Jalur 4: link episode di body paragraf (pola "Episode N: <link>")
        doc.select("div.entry-content.entry-content-single p a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.isNotBlank()) sources.add(fixUrl(href))
        }

        // Filter noise: anchor internal (#xxx), link balik ke domain sendiri -
        // ditemukan live-check 20/10/2026 ikut tertangkap selector paragraf.
        sources.removeAll { url ->
            url.startsWith("#") || url.trimEnd('/') == mainUrl.trimEnd('/')
        }

        sources
            .filterNot { url -> blacklistHosts.any { url.contains(it) } }
            .forEach { url ->
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }

        return true
    }
}
