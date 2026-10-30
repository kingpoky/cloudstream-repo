package com.kingpoky.klikxxi

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf

/**
 * NodrakorId — turunan engine muvipro (KlikxxiEngine), meski di APK asli
 * extends MainAPI langsung (bukan extends Klikxxi) - selector IDENTIK
 * (h2.entry-title>a, article.item, ul.muvipro-player-tabs, div#download,
 * data-litespeed-src) jadi aman reuse engine yang sudah live-verified.
 * RE dari com.lagradost.cloudxtream Nodrakorid.smali (key "pe" -> nodrakor22.space).
 * Domain diverifikasi live 20/10/2026: 2 sample (The Love Hypothesis via tab
 * player sk21play.my.id, Parasite 3 via watch.asiaplayer.cc) - loadLinks
 * tab-based terbukti jalan sempurna (beda dari KawanFilm yg tab-nya nol match).
 */
class NodrakorId : KlikxxiEngine() {
    // Domain pindah nodrakor22.space -> nodrakor22.asia (verifikasi live
    // 30/10/2026: .space 301 ke .asia, apex nodrakor21.space mati total).
    // HP yang masih memakai .space ikut terseret redirect untuk katalog,
    // tapi href hasil search sudah ber-URL .asia — pakai host final supaya
    // tidak bergantung pada redirect yang bisa dicabut kapan saja.
    override var mainUrl = "https://nodrakor22.asia"
    override var name = "NodrakorId"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "page/%d/?s&search=advanced&post_type=tv&genre=drama&country=korea" to "Korea Series",
        "page/%d/?s&search=advanced&post_type=tv&genre=c-drama&country=china" to "China Series",
        "genre/thai-drama/page/%d/" to "Thailand Series",
        "genre/korean-movie/page/%d/" to "Film Korea",
        "genre/movie/page/%d/" to "Movie",
        "genre/koleksi-series/page/%d/" to "All Series",
        "genre/variety-show/page/%d/" to "Variety Show",
    )
}
