package com.kingpoky.kazefuri

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import org.jsoup.Jsoup

// Util lokal (BUKAN API built-in CloudStream) dipakai di seluruh family
// Kazefuri (termasuk Nekokun/Auratail/Oploverz lewat warisan class).
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
 * Kazefuri — engine dasar family WP-anime-theme (mobius/mirror pattern).
 * Diturunkan via RE langsung dari com.lagradost.cloudxtream v4.9.43 (Kazefuri.smali),
 * ditulis ulang sendiri -- bukan salinan source pihak ketiga.
 * Domain diverifikasi live 20/10/2026.
 */
open class Kazefuri : MainAPI() {
    override var mainUrl = "https://kazefuri.space"
    override var name = "Kazefuri"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val interceptor by lazy { CloudflareKiller() }

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Rilisan Terbaru",
        "&status=ongoing&sub=&order=latest" to "Series Ongoing",
        "&status=completed&sub=&order=latest" to "Series Completed",
        "&status=hiatus&sub=&order=latest" to "Series Hiatus",
        "&status=&type=movie&order=latest" to "Movie",
    )

    protected suspend fun request(url: String, ref: String? = null) =
        app.get(url, interceptor = interceptor, referer = ref, timeout = 30)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = request("$mainUrl/anime/?page=$page${request.data}").document
        val items = doc.select("article[itemscope=itemscope]").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, items)
    }

    open fun toSearchResult(element: Element): SearchResponse? {
        val title = element.selectFirst("h2[itemprop=headline]")?.text()?.let { removeBloatx(it) }
            ?: return null
        val poster = element.selectFirst(".limit > img")?.let { getImageAttr(it) }
        val href = element.selectFirst("a.tip")?.attr("href") ?: return null
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = request("$mainUrl/?s=$query").document
        return doc.select("article[itemscope=itemscope]").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = request(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.let { removeBloatx(it) } ?: ""
        val poster = doc.selectFirst(".thumb > img")?.let { getImageAttr(it)?.substringBefore("?") }
        val genres = doc.select(".genxed > a").map { it.text() }
        val yearRegex = Regex("""\b(\d{4})\b""")
        val year = yearRegex.find(
            doc.selectFirst(".info-content > .spe > span.split")?.text()?.trim() ?: ""
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            doc.select(".info-content > .spe > span:nth-child(1)").text().trim()
                .replace("Status: ", "")
        )
        val synopsis = doc.select(".entry-content > p").text().trim()

        val episodes = doc.select(".eplister > ul > li").mapNotNull { li ->
            val epHref = li.select("a").attr("href")
            if (epHref.isBlank()) return@mapNotNull null
            val epNum = Regex("""([0-9]*)""").find(
                li.selectFirst("a > .epl-num")?.text()?.trim() ?: ""
            )?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epHref) { this.episode = epNum }
        }.reversed()

        return if (episodes.size > 1) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                posterUrl = poster
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
                plot = synopsis
                tags = genres
                showStatus = status
            }
        } else {
            val movieUrl = doc.select(".eplister > ul > li a").attr("href").ifBlank { url }
            newMovieLoadResponse(title, url, TvType.AnimeMovie, movieUrl) {
                posterUrl = poster
                this.year = year
                plot = synopsis
                tags = genres
            }
        }
    }

    protected fun getStatus(text: String): ShowStatus = when (text) {
        "Completed" -> ShowStatus.Completed
        "Ongoing" -> ShowStatus.Ongoing
        else -> ShowStatus.Completed
    }

    private fun getType(text: String): TvType = when {
        text.contains("OVA") -> TvType.OVA
        text.contains("Special") -> TvType.OVA
        text.contains("Movie") -> TvType.AnimeMovie
        else -> TvType.Anime
    }

    // Domain-domain shortlink/junk yang diblacklist (hasil RE, bukan tebakan)
    private val blacklistHosts = listOf(
        "short.icu", "mirrored.to", "mir.cr", "ko-fi.com", "sfl.gl", "t.me"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = request(data).document
        val sources = mutableSetOf<String>()

        // Jalur 1: .mobius > .mirror > option -> base64 decode -> meta[itemprop=embedUrl]
        doc.select(".mobius > .mirror > option").forEach { opt ->
            val decoded = base64Decode(opt.attr("value"))
            val embedUrl = Jsoup.parse(decoded)
                .select("div[itemtype=https://schema.org/VideoObject] meta[itemprop=embedUrl]")
                .attr("content")
            if (embedUrl.isNotBlank()) sources.add(fixUrl(embedUrl))
        }

        // Jalur 2: .mobius > .mirror > option -> base64 decode -> iframe[src]
        doc.select(".mobius > .mirror > option").forEach { opt ->
            val decoded = base64Decode(opt.attr("value"))
            val iframeSrc = Jsoup.parse(decoded).select("iframe").attr("src")
            if (iframeSrc.isNotBlank()) sources.add(fixUrl(iframeSrc))
        }

        // Jalur 3: .soraurlx a[href] langsung
        doc.select(".soraurlx a").forEach { a ->
            val href = a.attr("href")
            if (href.isNotBlank()) sources.add(fixUrl(href))
        }

        sources
            .map { it.replace("https://streamadblockplus.", "https://streamadblockplus.xyz") }
            .filterNot { url -> blacklistHosts.any { url.contains(it) } }
            .forEach { url ->
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }

        return true
    }
}
