package com.kingpoky.kazefuri

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Auratail — turunan engine Kazefuri TAPI override load() penuh dengan selector
 * episode/type berbeda dari induk (bukti smali: a > .playinfo > h3 + a > .epl-title,
 * bukan a > .epl-num seperti Kazefuri biasa; ada .desc.mindes buat deskripsi alternatif
 * dan .listupd > article[itemscope] buat daftar rekomendasi terkait).
 * RE dari com.lagradost.cloudxtream Auratail.smali (extends Kazefuri, key "r9g" -> filmanime.id).
 * Domain diverifikasi live 20/10/2026: 10 artikel ditemukan via search (filmanime.id,
 * sama backend dengan Nekokun -- dua brand nama beda, situs sama, sesuai evidence APK).
 */
class Auratail : Kazefuri() {
    override var mainUrl = "https://filmanime.id"
    override var name = "AuraTail"

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Rilisan Terbaru",
        "&status=ongoing&order=latest" to "Series Ongoing",
        "&status=completed&order=latest" to "Series Completed",
        "&status=hiatus&order=latest" to "Series Hiatus",
        "&type=movie&order=latest" to "Movie",
        "&type=live+action&order=latest" to "Live Action",
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = request(url).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.let { removeBloatx(it) } ?: ""
        val poster = doc.selectFirst(".thumb > img")?.let { getImageAttr(it) }
        val genres = doc.select(".genxed > a").map { it.text() }

        val yearRegex = Regex("""\b(\d{4})\b""")
        val splitText = doc.selectFirst(".info-content > .spe > span.split")?.text()?.trim() ?: ""
        val year = yearRegex.find(splitText)?.groupValues?.get(1)?.toIntOrNull()
            ?: yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull()

        val status = getStatus(
            doc.select(".info-content > .spe > span:nth-child(1)").text().trim()
                .replace("Status: ", "")
        )

        // Tipe diambil dari selector berbeda - khusus Auratail
        val typeText = doc.selectFirst(".info-content .spe span:contains(Type:)")?.ownText()

        // Sinopsis utama .entry-content, fallback .desc.mindes (khusus Auratail)
        val altDesc = doc.selectFirst(".desc.mindes")?.text()?.trim()
        val synopsis = doc.selectFirst(".entry-content > p")?.text()?.trim() ?: altDesc

        // Episode: selector epl-title/playinfo (BEDA dari induk Kazefuri yg pakai epl-num)
        var episodeEls = doc.select(".episodelist > ul > li")
        if (episodeEls.isEmpty()) episodeEls = doc.select(".eplister > ul > li")

        val episodes = episodeEls.mapNotNull { li ->
            val epHref = li.select("a").attr("href")
            if (epHref.isBlank()) return@mapNotNull null
            val numRegex = Regex("""([0-9]*)""")
            val numFromPlayinfo = numRegex.find(
                li.selectFirst("a > .playinfo > h3")?.text()?.trim() ?: ""
            )
            val numFromTitle = numRegex.find(
                li.selectFirst("a > .epl-title")?.text()?.trim() ?: ""
            )
            val epNum = (numFromPlayinfo ?: numFromTitle)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epHref) { this.episode = epNum }
        }.reversed()

        return if (typeText?.contains("Movie", ignoreCase = true) == true) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster
                this.year = year
                plot = synopsis
                tags = genres
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime) {
                posterUrl = poster
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
                plot = synopsis
                tags = genres
                showStatus = status
            }
        }
    }
}
