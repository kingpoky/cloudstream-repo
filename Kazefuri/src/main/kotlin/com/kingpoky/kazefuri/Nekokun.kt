package com.kingpoky.kazefuri

/**
 * Nekokun — turunan engine Kazefuri, beda mainUrl + tab menu mainPage custom
 * (nambah "Live Action" di antara kategori, lihat Nekokun.smali <init>).
 * RE dari com.lagradost.cloudxtream (extends Kazefuri, key "r9g" -> filmanime.id).
 * Domain diverifikasi live 20/10/2026: 10 artikel ditemukan via search.
 */
class Nekokun : Kazefuri() {
    override var mainUrl = "https://filmanime.id"
    override var name = "Nekokun"

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Rilisan Terbaru",
        "&type=live+action&sub=&order=latest" to "Live Action",
        "&type=movie&order=latest" to "Movie",
        "&status=ongoing&order=latest" to "Series Ongoing",
        "&status=completed&order=latest" to "Series Completed",
    )
}
