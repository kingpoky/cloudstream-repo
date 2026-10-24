package com.kingpoky.dramabox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPrivateKeySpec
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * DramaBox (com.storymatrix.drama) — RE dari APK resmi
 * DramaB0x_v6.9.4_69998_VlP_Premium.apk (versionCode 694).
 *
 * Skema dibuktikan dua jalur independen yang HARUS cocok:
 *  1. baksmali atas classes6/7/8.dex -> interface Retrofit `Bk/L1` dkk,
 *     annotation @POST("...") memberi SEMUA endpoint path verbatim.
 *  2. Private key RSA untuk signing adalah literal string YANG SAMA PERSIS
 *     (byte-for-byte) di dalam DEX dan di package npm publik @zhadev/dramabox
 *     (RE independen pihak lain atas APK yang sama) — dicocokkan via grep,
 *     bukan ditebak.
 * Live-verified 24/10/2026: bootstrap+theater+search+detail+batch/load semua
 * HTTP 200 dengan sign ini (lihat re/dramabox/verify_api.py di repo kerja).
 *
 * CATATAN KUNCI: DER PKCS8 key-nya punya CRT params (dP/dQ/qInv) yang SEDIKIT
 * tidak valid — Java `PKCS8EncodedKeySpec` standar gagal sign
 * (BadPaddingException) walau n/e/d/p/q sendiri matematis benar (OpenSSL/Node
 * toleran terhadap ini). Solusi: bentuk ulang via `RSAPrivateKeySpec(n, d)`
 * NON-CRT — signature yang dihasilkan terbukti identik byte-per-byte dengan
 * hasil Node crypto & PyCryptodome untuk pesan yang sama (self-check).
 *
 * Hanya chapter GRATIS (`isCharge == 0`) yang diekspos sebagai episode;
 * chapter VIP (`isCharge == 1`) di-skip — provider ini tidak membeli/bypass
 * konten berbayar.
 */
class Dramabox : MainAPI() {
    override var mainUrl = "https://sapi.dramaboxdb.com"
    override var name = "DramaBox"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val apiBase = "https://sapi.dramaboxdb.com/drama-box"

    /**
     * Akamai di depan sapi.dramaboxdb.com fingerprint ClientHello TLS:
     * OkHttpClient default (MODERN_TLS, advertise TLS1.2+1.3 sekaligus)
     * KENA 403 Access Denied -- dibuktikan live, 2x percobaan independen.
     * RESTRICTED_TLS (lebih sempit) LOLOS, juga dibuktikan live 3x
     * berturut dengan data user asli (uid, membershipInfo, dst).
     * App asli pakai OkHttp dgn TLS spec serupa ini (device Android asli
     * tidak advertise cipher suite selengkap JVM default).
     */
    private class TlsFixInterceptor : Interceptor {
        private val client = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.CLEARTEXT))
            .build()

        override fun intercept(chain: Interceptor.Chain): Response =
            client.newCall(chain.request()).execute()
    }

    private val tlsInterceptor = TlsFixInterceptor()

    // ---- RSA signing (lihat dokumentasi kelas untuk provenance) ----------

    private fun decodeObfuscated(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            var c = ch.code
            if (c in 33..126) {
                c -= 20
                if (c < 33) c += 126 - 33
            }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Membangun RSA PrivateKey dari potongan string identik dengan DEX asli. */
    private val privateKey by lazy {
        val part1 = "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC9Q4Y5QX5j08HrnbY3irfKdkEllAU2OORnAjlXDyCzcm2Z6ZRrGvtTZUAMelfU5PWS6XGEm3d4kJEKbXi4Crl8o2E/E3YJPk1lQD1d0JTdrvZleETN1ViHZFSQwS3L94Woh0E3TPebaEYq88eExvKu1tDdjSoFjBbgMezySnas5Nc2xF28"
        val encodedStr = "l|d,WL\$EI,?xyw+*)^#?U`[whXlG`-GZif,.jCxbKkaY\"{w*y]_jax^/1iVDdyg(Wbz+z/\$xVjCiH0lZf/d|%gZglW)\"~J,^~}w\"}m(E'eEunz)eyEy`XGaVF|_(Kw)|awUG\"'{{e#%\$0E.ffHVU++\$giHzdvC0ZLXG|U{aVUUYW{{YVU^x),J'If`nG|C[`ZF),xLv(-H'}ZIEyCfke0dZ%aU[V)\"V0}mhKvZ]Gw%-^a|m'`\\f}{(~kzi&zjG+|fXX0\$IH#j`+hfnME\"|fa/{.j.xf,\"LZ.K^bZy%c.W^/v{x#(J},Ua,ew#.##K(ki)\$LX{a-1\\MG/zL&JlEKEw'Hg|D&{EfuKYM[nGKx1V#lFu^V_LjVzw+n%+,Xd"
        val part2 = decodeObfuscated(encodedStr)
        val part3 = "x52e71nafqfbjXxZuEtpu92oJd6A9mWbd0BZTk72ZHUmDcKcqjfcEH19SWOphMJFYkxU5FRoIEr3/zisyTO4Mt33ZmwELOrY9PdlyAAyed7ZoH+hlTr7c025QROvb2LmqgRiUT56tMECgYEA+jH5m6iMRK6XjiBhSUnlr3DzRybwlQrtIj5sZprWe2my5uYHG3jbViYIO7GtQvMTnDrBCxNhuM6dPrL0cRnbsp/iBMXe3pyjT/aWveBkn4R+UpBsnbtDn28r1MZpCDtr5UNc0TPj4KFJvjnV/e8oGoyYEroECqcw1LqNOGDiLhkCgYEAwaemNePYrXW+MVX/hatfLQ96tpxwf7yuHdENZ2q5AFw73GJWYvC8VY+TcoKPAmeoCUMltI3TrS6K5Q/GoLd5K2BsoJrSxQNQFd3ehWAtdOuPDvQ5rn/2fsvgvc3rOvJh7uNnwEZCI/45WQg+UFWref4PPc+ArNtp9Xj2y7LndwkCgYARojIQeXmhYZjG6JtSugWZLuHGkwUDzChYcIPd"
        val part4 = "W25ndluokG/RzNvQn4+W/XfTryQjr7RpXm1VxCIrCBvYWNU2KrSYV4XUtL+B5ERNj6In6AOrOAifuVITy5cQQQeoD+AT4YKKMBkQfO2gnZzqb8+ox130e+3K/mufoqJPZeyrCQKBgC2fobjwhQvYwYY+DIUharri+rYrBRYTDbJYnh/PNOaw1CmHwXJt5PEDcml3+NlIMn58I1X2U/hpDrAIl3MlxpZBkVYFI8LmlOeR7ereTddN59ZOE4jY/OnCfqA480Jf+FKfoMHby5lPO5OOLaAfjtae1FhrmpUe3EfIx9wVuhKBAoGBAPFzHKQZbGhkqmyPW2ctTEIWLdUHyO37fm8dj1WjN4wjRAI4ohNiKQJRh3QE11E1PzBTl9lZVWT8QtEsSjnrA/tpGr378fcUT7WGBgTmBRaAnv1P1n/Tp0TSvh5XpIhhMuxcitIgrhYMIG3GbP9JNAarxO/qPW6Gi0xWaF7il7Or"
        val pem = part1 + part2 + part3 + part4
        val der = Base64.getDecoder().decode(pem)

        // Parse PKCS8 manually (ASN.1 DER) to pull raw n/d -- avoids Java's
        // strict CRT-consistency check that rejects this particular key
        // when loaded via the normal PKCS8EncodedKeySpec path.
        val (n, d) = extractModulusAndExponent(der)
        val kf = KeyFactory.getInstance("RSA")
        kf.generatePrivate(RSAPrivateKeySpec(n, d))
    }

    /**
     * Minimal ASN.1 DER walker: PKCS8 PrivateKeyInfo -> RSAPrivateKey ->
     * (version, n, e, d, p, q, dP, dQ, qInv). Kita hanya butuh n (index 1)
     * dan d (index 3); mengambilnya langsung dari struktur menghindari
     * java.security's PKCS8 loader yang memvalidasi SELURUH CRT quintuple.
     */
    private fun extractModulusAndExponent(der: ByteArray): Pair<BigInteger, BigInteger> {
        var pos = 0
        fun readLen(): Int {
            val first = der[pos++].toInt() and 0xFF
            if (first < 0x80) return first
            val numBytes = first and 0x7F
            var len = 0
            repeat(numBytes) { len = (len shl 8) or (der[pos++].toInt() and 0xFF) }
            return len
        }
        fun expectTag(tag: Int) {
            require((der[pos++].toInt() and 0xFF) == tag) { "unexpected ASN.1 tag at $pos" }
        }
        fun readInt(): BigInteger {
            expectTag(0x02)
            val len = readLen()
            val bytes = der.copyOfRange(pos, pos + len)
            pos += len
            return BigInteger(1, if (bytes[0] < 0) byteArrayOf(0) + bytes else bytes)
        }

        // PrivateKeyInfo ::= SEQUENCE { version, algorithm, privateKey OCTET STRING }
        expectTag(0x30); readLen()
        readInt() // version
        expectTag(0x30)
        val algLen = readLen()
        pos += algLen // AlgorithmIdentifier, skip
        expectTag(0x04); readLen() // OCTET STRING wrapping RSAPrivateKey

        // RSAPrivateKey ::= SEQUENCE { version, n, e, d, p, q, dP, dQ, qInv }
        expectTag(0x30); readLen()
        readInt() // version
        val n = readInt()
        readInt() // e
        val d = readInt()
        return n to d
    }

    private fun signString(s: String): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(s.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    // ---- Session / device identity (regenerated per plugin lifetime) -----

    private val deviceId = UUID.randomUUID().toString()
    private val androidId = "00000000" + (1..16).map { "0123456789abcdef".random() }.joinToString("") + "00000000"
    private var token: String? = null
    private val tokenLock = Mutex()

    private fun randomActiveTime() = Random.nextInt(0, 60000).toString()
    private fun randomAfid(ts: Long) = "$ts-${Random.nextLong(0, 10_000_000_000_000_000L)}"
    private fun randomInstanceId() = (1..32).map { "0123456789abcdef".random() }.joinToString("")

    private fun localTimeNow(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("GMT+07:00")
        return fmt.format(java.util.Date()) + " +0700"
    }

    private fun baseHeaders(ts: Long, tn: String, sn: String): Map<String, String> = mapOf(
        "active-time" to randomActiveTime(),
        "afid" to randomAfid(ts),
        "android-id" to androidId,
        "apn" to "0",
        "brand" to "Xiaomi",
        "build" to "Build/QQ3A.200805.001",
        "cid" to "DAUAG1064236",
        "content-type" to "application/json; charset=UTF-8",
        "country-code" to "ID",
        "current-language" to "in",
        "device-id" to deviceId,
        "device-score" to "55",
        "host" to "sapi.dramaboxdb.com",
        "ins" to ts.toString(),
        "instanceid" to randomInstanceId(),
        "is_emulator" to "0",
        "is_root" to "1",
        "is_vpn" to "1",
        "language" to "in",
        "lat" to "0",
        "local-time" to localTimeNow(),
        "locale" to "in_ID",
        "mbid" to "60000000000",
        "mcc" to "510",
        "mchid" to "DAUAG1050238",
        "md" to "V2309A",
        "mf" to "VIVO",
        "nchid" to "DRA1000000",
        "ov" to "9",
        "over-flow" to "new-fly",
        "p" to "51",
        "package-name" to "com.storymatrix.drama",
        "pline" to "ANDROID",
        "srn" to "900x1600",
        "store-source" to "store_google",
        "time-zone" to "+0800",
        "tn" to tn,
        "tz" to "-480",
        "user-agent" to "okhttp/4.10.0",
        "userid" to "359146421",
        "version" to "490",
        "vn" to "4.9.0",
        "sn" to sn,
    )

    private suspend fun ensureToken(): String {
        token?.let { return it }
        tokenLock.withLock {
            token?.let { return it }
            val ts = System.currentTimeMillis()
            val payload = mapOf("distinctId" to null)
            val body = payload.toJson()
            val signPayload = "timestamp=$ts$body$deviceId$androidId"
            val sn = signString(signPayload)
            val headers = baseHeaders(ts, "", sn)
            val res = try {
                app.post(
                    "$apiBase/ap001/bootstrap?timestamp=$ts",
                    headers = headers,
                    requestBody = body.toByteArray(Charsets.UTF_8)
                        .toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()),
                    interceptor = tlsInterceptor,
                )
            } catch (e: Exception) {
                // Exception jaringan (DNS/TLS/timeout) -- beda kelas dari
                // respons sukses yang isinya tidak terduga (lihat di bawah).
                throw ErrorLoadingException(
                    "DramaBox: koneksi gagal (${e.javaClass.simpleName}: ${e.message?.take(120)})"
                )
            }
            val parsed = tryParseJson<BootstrapResponse>(res.text)
            val t = parsed?.data?.user?.token ?: throw ErrorLoadingException(
                "DramaBox: token kosong, HTTP ${res.code}, body: ${res.text.take(150)}"
            )
            token = t
            return t
        }
    }

    private suspend fun sapiPost(endpoint: String, payload: Map<String, Any?>): String {
        val t = ensureToken()
        val ts = System.currentTimeMillis()
        val body = payload.toJson()
        val tn = "Bearer $t"
        val signPayload = "timestamp=$ts$body$deviceId$androidId$tn"
        val sn = signString(signPayload)
        val headers = baseHeaders(ts, tn, sn)
        val res = app.post(
            "$apiBase$endpoint?timestamp=$ts",
            headers = headers,
            requestBody = body.toByteArray(Charsets.UTF_8)
                .toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()),
            interceptor = tlsInterceptor,
        )
        if (res.code == 401) {
            // token invalid/expired -> refresh sekali
            token = null
            val t2 = ensureToken()
            val tn2 = "Bearer $t2"
            val ts2 = System.currentTimeMillis()
            val signPayload2 = "timestamp=$ts2$body$deviceId$androidId$tn2"
            val sn2 = signString(signPayload2)
            val res2 = app.post(
                "$apiBase$endpoint?timestamp=$ts2",
                headers = baseHeaders(ts2, tn2, sn2),
                requestBody = body.toByteArray(Charsets.UTF_8)
                    .toRequestBody("application/json; charset=UTF-8".toMediaTypeOrNull()),
                interceptor = tlsInterceptor,
            )
            return res2.text
        }
        return res.text
    }

    // ---- MainAPI ----------------------------------------------------------

    override val mainPage = mainPageOf(
        "theater:48" to "Beranda",
        "theater:175" to "Trending",
        "theater:205" to "Anggota (preview)",
        "classify" to "Semua Drama",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = if (request.data == "classify") {
            val body = sapiPost(
                "/he001/classify",
                mapOf(
                    "typeList" to if (page == 1) emptyList<Any>() else listOf(
                        mapOf("type" to 1, "value" to ""),
                        mapOf("type" to 2, "value" to ""),
                        mapOf("type" to 3, "value" to ""),
                        mapOf("type" to 4, "value" to ""),
                        mapOf("type" to 5, "value" to "1"),
                    ),
                    "showLabels" to false,
                    "pageNo" to page.toString(),
                    "pageSize" to "20",
                )
            )
            val parsed = tryParseJson<ClassifyResponse>(body)
            parsed?.data?.classifyBookList?.records.orEmpty().mapNotNull { toSearchResult(it) }
        } else {
            val channelId = request.data.substringAfter(":").toIntOrNull() ?: 48
            val body = sapiPost(
                "/he001/theater",
                mapOf(
                    "newChannelStyle" to 1,
                    "isNeedRank" to 1,
                    "pageNo" to page,
                    "index" to 1,
                    "channelId" to channelId,
                )
            )
            val parsed = tryParseJson<TheaterResponse>(body)
            parsed?.data?.columnVoList.orEmpty()
                .flatMap { it.bookList.orEmpty() }
                .mapNotNull { toSearchResult(it) }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(book: Book): SearchResponse? {
        val id = book.bookId ?: return null
        val name = book.bookName ?: return null
        return newTvSeriesSearchResponse(name, id, TvType.AsianDrama) {
            posterUrl = book.coverWap ?: book.cover
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val body = sapiPost(
            "/search/search",
            mapOf(
                "searchSource" to "search_button",
                "pageNo" to 1,
                "pageSize" to 20,
                "from" to "search_sug",
                "keyword" to query,
            )
        )
        val parsed = tryParseJson<SearchApiResponse>(body)
        return parsed?.data?.searchList.orEmpty().mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        // `url` di sini adalah bookId mentah (lihat toSearchResult).
        val bookId = url
        val body = sapiPost(
            "/chapterv2/detail",
            mapOf("needRecommend" to false, "from" to "book_album", "bookId" to bookId)
        )
        val parsed = tryParseJson<DetailResponse>(body)
        val data = parsed?.data

        // Nama/poster tidak selalu ikut di chapterv2/detail -- ambil dari search
        // sebagai fallback kalau kosong (endpoint ini fokus ke daftar chapter).
        var title = bookId
        var poster: String? = null
        var synopsis: String? = null
        try {
            val sres = sapiPost(
                "/search/search",
                mapOf("searchSource" to "detail_fallback", "pageNo" to 1, "pageSize" to 1,
                    "from" to "detail_fallback", "keyword" to bookId)
            )
            val sp = tryParseJson<SearchApiResponse>(sres)
            val match = sp?.data?.searchList?.firstOrNull { it.bookId == bookId }
            if (match != null) {
                title = match.bookName ?: title
                poster = match.cover
                synopsis = match.introduction
            }
        } catch (_: Exception) { /* fallback best-effort saja */ }

        val chapterIndexAndCharge = fetchAllChapterMeta(bookId)
        val episodes = chapterIndexAndCharge
            .filter { it.second == 0 } // hanya chapter GRATIS (isCharge == 0)
            .map { (index, _) ->
                newEpisode(LoadData(bookId, index).toJson()) {
                    this.episode = index + 1
                    this.name = "Episode ${index + 1}"
                }
            }

        return newTvSeriesLoadResponse(title, bookId, TvType.AsianDrama, episodes) {
            posterUrl = poster
            plot = synopsis ?: data?.let { "Chapter gratis: ${episodes.size}" }
        }
    }

    /** Mengambil (chapterIndex, isCharge) untuk SELURUH chapter, step 6 per call. */
    private suspend fun fetchAllChapterMeta(bookId: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var idx = 1
        var total = -1
        var guard = 0
        while (guard++ < 60) { // batas aman ~360 chapter
            val body = sapiPost(
                "/chapterv2/batch/load",
                mapOf(
                    "boundaryIndex" to 0,
                    "comingPlaySectionId" to -1,
                    "index" to idx,
                    "currencyPlaySource" to "discover_new_rec_new",
                    "needEndRecommend" to 0,
                    "currencyPlaySourceName" to "",
                    "preLoad" to false,
                    "rid" to "",
                    "pullCid" to "",
                    "loadDirection" to 0,
                    "bookId" to bookId,
                )
            )
            val parsed = tryParseJson<BatchLoadResponse>(body)
            val chapters = parsed?.data?.chapterList.orEmpty()
            if (total < 0) total = parsed?.data?.chapterCount ?: 0
            if (chapters.isEmpty()) break
            chapters.forEach { ch ->
                val i = ch.chapterIndex ?: return@forEach
                result.add(i to (ch.isCharge ?: 1))
            }
            idx += 6
            if (idx > total + 6) break
        }
        return result.distinctBy { it.first }.sortedBy { it.first }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LoadData>(data) ?: return false
        val body = sapiPost(
            "/chapterv2/batch/load",
            mapOf(
                "boundaryIndex" to 0,
                "comingPlaySectionId" to -1,
                "index" to (loadData.chapterIndex + 1).coerceAtLeast(1),
                "currencyPlaySource" to "discover_new_rec_new",
                "needEndRecommend" to 0,
                "currencyPlaySourceName" to "",
                "preLoad" to false,
                "rid" to "",
                "pullCid" to "",
                "loadDirection" to 0,
                "bookId" to loadData.bookId,
            )
        )
        val parsed = tryParseJson<BatchLoadResponse>(body)
        val chapter = parsed?.data?.chapterList.orEmpty()
            .firstOrNull { it.chapterIndex == loadData.chapterIndex }
            ?: return false

        if ((chapter.isCharge ?: 1) != 0) return false // jaga-jaga: VIP tidak diputar

        var found = false
        chapter.cdnList.orEmpty().forEach { cdn ->
            cdn.videoPathList.orEmpty().forEach { vp ->
                val path = vp.videoPath ?: return@forEach
                found = true
                callback(
                    ExtractorLink(
                        source = this.name,
                        name = "${this.name} ${vp.quality ?: ""}p".trim(),
                        url = path,
                        referer = "",
                        quality = vp.quality ?: Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO,
                    )
                )
            }
        }
        return found
    }

    // ---- Data classes (schema sesuai field yang terbukti ada di respons live) --

    data class LoadData(val bookId: String, val chapterIndex: Int)

    data class BootstrapResponse(@JsonProperty("data") val data: BootstrapData?)
    data class BootstrapData(@JsonProperty("user") val user: BootstrapUser?)
    data class BootstrapUser(@JsonProperty("token") val token: String?)

    data class TheaterResponse(@JsonProperty("data") val data: TheaterData?)
    data class TheaterData(@JsonProperty("columnVoList") val columnVoList: List<ColumnVo>?)
    data class ColumnVo(@JsonProperty("bookList") val bookList: List<Book>?)

    data class ClassifyResponse(@JsonProperty("data") val data: ClassifyData?)
    data class ClassifyData(@JsonProperty("classifyBookList") val classifyBookList: ClassifyBookList?)
    data class ClassifyBookList(@JsonProperty("records") val records: List<Book>?, @JsonProperty("isMore") val isMore: Int?)

    data class SearchApiResponse(@JsonProperty("data") val data: SearchApiData?)
    data class SearchApiData(@JsonProperty("searchList") val searchList: List<Book>?)

    data class Book(
        @JsonProperty("bookId") val bookId: String?,
        @JsonProperty("bookName") val bookName: String?,
        @JsonProperty("cover") val cover: String?,
        @JsonProperty("coverWap") val coverWap: String?,
        @JsonProperty("introduction") val introduction: String?,
        @JsonProperty("chapterCount") val chapterCount: Int?,
    )

    data class DetailResponse(@JsonProperty("data") val data: DetailData?)
    data class DetailData(@JsonProperty("list") val list: List<Any>?)

    data class BatchLoadResponse(@JsonProperty("data") val data: BatchLoadData?)
    data class BatchLoadData(
        @JsonProperty("chapterCount") val chapterCount: Int?,
        @JsonProperty("chapterList") val chapterList: List<ChapterItem>?,
    )
    data class ChapterItem(
        @JsonProperty("chapterIndex") val chapterIndex: Int?,
        @JsonProperty("chapterName") val chapterName: String?,
        @JsonProperty("isCharge") val isCharge: Int?,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>?,
    )
    data class CdnItem(@JsonProperty("videoPathList") val videoPathList: List<VideoPathItem>?)
    data class VideoPathItem(
        @JsonProperty("videoPath") val videoPath: String?,
        @JsonProperty("quality") val quality: Int?,
    )
}
