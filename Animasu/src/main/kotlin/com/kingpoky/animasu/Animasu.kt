package com.kingpoky.animasu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

// Util lokal (BUKAN API built-in CloudStream).
fun getImageAttr(el: org.jsoup.nodes.Element): String? {
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
 * Animasu — tema anime populer (div.bs/div.tt/div.infox/ul#daftarepisode),
 * BEDA markup dari Kazefuri tapi SAMA PERSIS mekanisme loadLinks
 * (.mobius > .mirror > option base64-encoded iframe) - reuse logic decode,
 * TIDAK extends Kazefuri krn struktur listing/episode beda total.
 * RE dari com.lagradost.cloudxtream Animasu.smali (key "e3z" -> animasu.love).
 * Domain diverifikasi live 20/10/2026: 2 sample (Dark Summoner to Dekiteiru,
 * Boruto) - 4 & 6 server embed valid (blogger.com, vidhidepro.com, abyssplayer.com).
 */
class Animasu : MainAPI() {
    override var mainUrl = "https://animasu.love"
    override var name = "Animasu"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "status=&tipe=&urutan=publikasi" to "Baru ditambahkan",
        "status=&tipe=Movie&urutan=update" to "Movie Terbaru",
        "status=&tipe=TV&urutan=update" to "Series Episode Terbaru",
        "status=&tipe=Movie&urutan=populer" to "Movie Terpopuler",
        "status=&tipe=TV&urutan=populer" to "Series Terpopuler",
    )

    private fun toSearchResult(element: org.jsoup.nodes.Element): SearchResponse? {
        val tt = element.selectFirst("div.tt") ?: return null
        val title = tt.text().trim()
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val poster = element.selectFirst("img")?.let { getImageAttr(it) }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.listupd div.bs").mapNotNull { toSearchResult(it) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/?${request.data}&halaman=$page").document
        val items = doc.select("div.listupd div.bs").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.infox h1")?.text()?.trim() ?: ""
        val poster = doc.selectFirst("div.bigcontent img")?.let { getImageAttr(it) }

        val episodes = doc.select("ul#daftarepisode > li").mapNotNull { li ->
            val epHref = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(li.selectFirst("span.epx")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epHref) { this.episode = epNum }
        }.reversed()

        return if (episodes.size > 1) {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                posterUrl = poster
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster
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
        val sources = mutableSetOf<String>()

        // Pola .mobius>.mirror>option sama persis Kazefuri: base64 decode -> iframe[src]
        doc.select(".mobius > .mirror > option").forEach { opt ->
            val decoded = base64Decode(opt.attr("value"))
            val iframeSrc = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
            if (!iframeSrc.isNullOrBlank()) sources.add(fixUrl(iframeSrc))
        }

        sources.forEach { url ->
            loadExtractor(url, mainUrl, subtitleCallback, callback)
        }
        return sources.isNotEmpty()
    }
}
