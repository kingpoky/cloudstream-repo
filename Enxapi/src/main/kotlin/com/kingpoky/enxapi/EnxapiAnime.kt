package com.kingpoky.enxapi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

/**
 * Enxapi Anime — provider kedua di modul yang sama (pola Kazefuri:
 * satu modul gradle, beberapa registerMainAPI).
 *
 * Gerbang API dan bentuk SPA-nya identik dengan [Enxapi] (lihat docstring
 * di sana), tapi jalur playback-nya BERBEDA SAMA SEKALI dan jauh lebih
 * pendek: tidak ada iframe, tidak ada extractor pihak ketiga.
 *
 * `/episode/<slug>/sources` mengembalikan 28 sumber siap pakai bertingkat
 * 360/480/720/1080 dengan `type` hls atau mp4. Field `url`-nya berbentuk
 * `stream/proxy?t=<token>` — RELATIF terhadap /api/v1.
 *
 * TEMUAN PENTING (RE 21/10/2026): token `t=` BUKAN JWT bertanda tangan.
 * Isinya base64url dua segmen; segmen pertama adalah JSON polos:
 *     {"u":"<URL CDN asli>","h":{Origin,Referer},"e":<unix expiry>}
 * Jadi URL CDN sebenarnya dan header yang dibutuhkannya ada di dalam token
 * itu sendiri, dalam teks terbuka. Provider ini MEMBUKA token dan memutar
 * langsung dari CDN, bukan lewat proxy enxapi:
 *   - nol bandwidth menumpang server mereka (mereka cuma pembungkus),
 *   - tidak ada single point of failure kalau proxy di-rate-limit,
 *   - header Origin/Referer yang benar ikut dari token, bukan ditebak.
 * Kalau token gagal dibuka (format berubah), fallback ke URL proxy — itu
 * tetap sah dan terbukti memutar.
 *
 * Diverifikasi ffprobe 21/10/2026, One Piece eps 1180:
 *   proxy  -> h264 1920x1080 1416s
 *   CDN langsung (edge02.upbolt.to) -> h264 1920x1080 1416s  (identik)
 *   56/56 token dari 2 episode terbuka bersih; `e` berumur 6 jam.
 * Host CDN yang muncul: upbolt.to, pixeldrain.com, s3.animeverse.id,
 * archive.org, cdn.odcloud.net.
 */
class EnxapiAnime : MainAPI() {
    override var mainUrl = "https://enxapi.id"
    override var name = "Enxapi Anime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val api get() = "$mainUrl/api/v1"

    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private suspend inline fun <reified T : Any> apiGet(path: String): T? =
        tryParseJson<T>(app.get(api + path, headers = apiHeaders, timeout = 30).text)

    override val mainPage = mainPageOf(
        "/anime/ongoing" to "Sedang Tayang",
        "/anime/complete" to "Sudah Tamat",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = apiGet<AnimeList>("${request.data}?page=$page")
        val items = data?.items.orEmpty().map { item ->
            newAnimeSearchResponse(item.title, item.slug, TvType.Anime) {
                posterUrl = item.cover_url
                addSub(Regex("""(\d+)""").find(item.episode.orEmpty())?.value?.toIntOrNull())
            }
        }
        return newHomePageResponse(request.name, items, hasNext = data?.has_next ?: false)
    }

    // /search mengembalikan ARRAY telanjang (bukan {items:[...]}) — beda dari
    // /donghua/search. Jangan samakan bentuknya.
    override suspend fun search(query: String): List<SearchResponse> =
        apiGet<List<AnimeSearchItem>>("/search?q=${query.encodeUri()}").orEmpty().map {
            newAnimeSearchResponse(it.title, it.slug, TvType.Anime) {
                posterUrl = it.cover_url
            }
        }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/").ifBlank { url }
        val d = apiGet<AnimeDetail>("/anime/$slug") ?: return null

        val episodes = d.episodes.orEmpty().map { ep ->
            newEpisode(ep.slug) {
                this.name = ep.title
                this.episode = Regex("""(\d+)""").find(ep.title.orEmpty())?.value?.toIntOrNull()
            }
        }.reversed()

        val type = when {
            d.type.equals("Movie", true) -> TvType.AnimeMovie
            d.type.equals("OVA", true) || d.type.equals("ONA", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(d.title, "$mainUrl/anime/$slug", type) {
            posterUrl = d.cover_url
            plot = d.synopsis
            japName = d.japanese_title?.ifBlank { null }
            tags = d.genres.orEmpty().map { it.name }
            year = Regex("""\b(\d{4})\b""").find(d.release_date.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            showStatus = when (d.status) {
                "Ongoing" -> ShowStatus.Ongoing
                "Completed" -> ShowStatus.Completed
                else -> null
            }
            d.score?.toDoubleOrNull()?.let { this.score = Score.from(it, 10) }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.substringAfterLast("/").ifBlank { data }
        val sources = apiGet<SourceList>("/episode/$slug/sources")?.sources.orEmpty()
        if (sources.isEmpty()) return false

        sources.forEach { s ->
            val raw = s.url ?: return@forEach
            // `url` relatif terhadap /api/v1 — jalur proxy, dipakai sebagai cadangan.
            val proxied = if (raw.startsWith("http")) raw else "$api/${raw.removePrefix("/")}"
            val direct = ProxyToken.unwrap(raw)
            val target = direct?.url ?: proxied
            val linkType =
                if (s.type.equals("hls", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val label = "${s.provider ?: name} ${s.quality.orEmpty()}".trim()

            callback(
                newExtractorLink(name, label, target, linkType) {
                    this.quality = getQualityFromName(s.quality)
                    // Header CDN datang dari token; untuk jalur proxy cukup Referer situs.
                    this.referer = direct?.headers?.get("Referer") ?: "$mainUrl/"
                    direct?.headers?.let { this.headers = it }
                }
            )
        }
        return true
    }

    private fun String.encodeUri() = java.net.URLEncoder.encode(this, "UTF-8")
}

// --- bentuk JSON API anime -----------------------------------------------

data class AnimeList(
    @JsonProperty("items") val items: List<AnimeListItem>? = null,
    @JsonProperty("has_next") val has_next: Boolean? = null,
)

data class AnimeListItem(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("cover_url") val cover_url: String? = null,
    @JsonProperty("episode") val episode: String? = null,
)

data class AnimeSearchItem(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("cover_url") val cover_url: String? = null,
)

data class AnimeDetail(
    @JsonProperty("title") val title: String,
    @JsonProperty("japanese_title") val japanese_title: String? = null,
    @JsonProperty("cover_url") val cover_url: String? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("release_date") val release_date: String? = null,
    @JsonProperty("score") val score: String? = null,
    @JsonProperty("genres") val genres: List<Genre>? = null,
    @JsonProperty("episodes") val episodes: List<AnimeEpisode>? = null,
)

data class AnimeEpisode(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("title") val title: String? = null,
)

data class SourceList(@JsonProperty("sources") val sources: List<SourceItem>? = null)

data class SourceItem(
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("provider") val provider: String? = null,
)
