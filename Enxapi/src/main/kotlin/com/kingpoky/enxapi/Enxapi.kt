package com.kingpoky.enxapi

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

/**
 * User-Agent browser, dipakai SEMUA panggilan API di modul ini.
 *
 * Cloudflare di depan enxapi menolak UA `okhttp/...` — default NiceHttp
 * CloudStream — dengan halaman 403 "Attention Required" (~4,5 KB HTML),
 * BUKAN `{"error":"akses ditolak"}` 25 byte milik aplikasi. Dua gerbang
 * berbeda, dua lapis berbeda. CDN video tidak peduli UA; hanya API.
 *
 * Top-level di satu modul gradle = terlihat semua file di modul ini.
 */
const val UA_BROWSER =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/**
 * Enxapi — provider donghua sub Indo.
 *
 * Situsnya React SPA: HTML-nya kosong, SEMUA data datang dari JSON API
 * `https://enxapi.id/api/v1`. Jadi provider ini tidak men-scrape markup sama
 * sekali; tidak ada selector CSS yang bisa patah karena ganti tema.
 *
 * GERBANG AKSES (hasil RE 21/10/2026): request ke /api/v1 dijawab
 * 403 {"error":"akses ditolak"} kecuali DUA header ini hadir bersamaan:
 *   - Accept-Language  (nilai apa pun; kosong/absen = tolak)
 *   - salah satu dari Referer / Origin / Sec-Fetch-Site
 * Dibuktikan dengan uji drop-one-by-one atas 7 header. Bukan Cloudflare —
 * CF di depan meneruskan apa adanya; penolakan datang dari aplikasi.
 *
 * Playback diverifikasi ffprobe (byte sungguhan, bukan HTTP 200) pada
 * The Guardian of Daxia eps 07: RPM Share 1280x720 dan Streamruby 640x360
 * sama-sama decode 717 detik. Mirror lain (Okru/Odysee/VidHide) balas 5XX
 * dari IP datacenter, Rumble/D-Tube/Abyss perlu browser — tetap dilempar ke
 * extractor bawaan app karena di HP jalurnya berbeda.
 */
class Enxapi : MainAPI() {
    override var mainUrl = "https://enxapi.id"
    override var name = "Enxapi"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val api get() = "$mainUrl/api/v1"

    // User-Agent WAJIB browser. Cloudflare di depan enxapi memblokir UA
    // `okhttp/...` (default CloudStream) dengan halaman 403 "Attention
    // Required" SEBELUM request sampai ke aplikasi — gerbang lapis pertama,
    // terpisah dari gerbang Accept-Language/Referer milik aplikasi.
    // Gejalanya di HP: "No links found" walau katalog tampil normal dari
    // pemeriksa server yang kebetulan memakai UA browser.
    private val apiHeaders = mapOf(
        "User-Agent" to UA_BROWSER,
        "Accept" to "application/json",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private suspend inline fun <reified T : Any> apiGet(path: String): T? =
        tryParseJson<T>(app.get(api + path, headers = apiHeaders, timeout = 30).text)

    // `order` dipakai apa adanya oleh backend; `status` difilter server-side.
    override val mainPage = mainPageOf(
        "order=update" to "Rilisan Terbaru",
        "order=popular" to "Populer",
        "status=Ongoing" to "Ongoing",
        "status=Completed" to "Completed",
    )

    private fun DonghuaItem.toSearchResponse(): SearchResponse =
        newAnimeSearchResponse(title, slug, TvType.Anime) {
            posterUrl = cover_url
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = apiGet<DonghuaList>("/donghua?page=$page&${request.data}")
        val items = data?.items.orEmpty().map { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = data?.has_next ?: false)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        apiGet<DonghuaList>("/donghua/search?q=${query.encodeUri()}")
            ?.items.orEmpty().map { it.toSearchResponse() }

    override suspend fun load(url: String): LoadResponse? {
        // `url` di sini adalah slug (itu yang kita simpan di SearchResponse),
        // tapi app bisa juga menyerahkan URL penuh dari bookmark lama.
        val slug = url.substringAfterLast("/").ifBlank { url }
        val d = apiGet<DonghuaDetail>("/donghua/detail/$slug") ?: return null

        val episodes = d.episodes.orEmpty().map { ep ->
            newEpisode(ep.slug) {
                this.name = ep.title
                this.episode = ep.number?.trimStart('0')?.toIntOrNull()
            }
        }.reversed()

        return newAnimeLoadResponse(d.title, "$mainUrl/donghua/detail/$slug", TvType.Anime) {
            posterUrl = d.cover_url
            plot = d.synopsis
            tags = d.genres.orEmpty().map { it.name }
            // `released_at` berbentuk "Sep 30, 2026" — ambil tahunnya saja.
            year = Regex("""\b(\d{4})\b""").find(d.released_at.orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            showStatus = when (d.status) {
                "Ongoing" -> ShowStatus.Ongoing
                "Completed" -> ShowStatus.Completed
                else -> null
            }
            d.rating?.toDoubleOrNull()?.let { this.score = Score.from(it, 10) }
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
        val watch = apiGet<WatchResponse>("/donghua/watch/$slug") ?: return false

        // Referer halaman tonton — beberapa host (RPM) memakainya untuk
        // menyusun URL media, jadi harus halaman asli bukan root.
        val pageRef = "$mainUrl/donghua/watch/$slug"

        val urls = buildList {
            watch.mirrors.orEmpty().forEach { it.iframe_url?.let(::add) }
            watch.default_stream_url?.let(::add)
        }.distinct()

        var any = false
        urls.forEach { raw ->
            val url = httpsify(raw)
            any = true
            // RPM keluarga (anichin.rpmvid.com/#id) bukan embed HTML biasa:
            // media disusun lewat /api/v1/video dengan payload AES-CBC.
            // Extractor bawaan app tidak mengenalinya, jadi ditangani sendiri.
            if (url.substringAfter("://").substringBefore("/").contains("rpm") &&
                url.contains("#")
            ) {
                RpmResolver.resolve(url, callback)
            } else {
                loadExtractor(url, pageRef, subtitleCallback, callback)
            }
        }
        return any
    }

    private fun String.encodeUri() = java.net.URLEncoder.encode(this, "UTF-8")
}

// --- bentuk JSON API (hanya field yang benar-benar dipakai) ---------------

data class DonghuaList(
    @JsonProperty("items") val items: List<DonghuaItem>? = null,
    @JsonProperty("has_next") val has_next: Boolean? = null,
)

data class DonghuaItem(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("cover_url") val cover_url: String? = null,
)

data class DonghuaDetail(
    @JsonProperty("title") val title: String,
    @JsonProperty("cover_url") val cover_url: String? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("released_at") val released_at: String? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("genres") val genres: List<Genre>? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null,
)

data class Genre(@JsonProperty("name") val name: String)

data class EpisodeItem(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("number") val number: String? = null,
)

data class WatchResponse(
    @JsonProperty("default_stream_url") val default_stream_url: String? = null,
    @JsonProperty("mirrors") val mirrors: List<Mirror>? = null,
)

data class Mirror(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("iframe_url") val iframe_url: String? = null,
)
