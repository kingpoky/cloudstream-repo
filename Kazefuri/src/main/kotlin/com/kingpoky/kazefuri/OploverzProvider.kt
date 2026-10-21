package com.kingpoky.kazefuri

/**
 * OploverzProvider — turunan engine Kazefuri (meski di APK asli extends
 * MainAPI langsung, bukan extends Kazefuri - selector 100% identik
 * (h2[itemprop=headline], article[itemscope], .eplister>ul>li, .mobius>.mirror>option)
 * jadi aman reuse, sama prinsip DRY spt NodrakorId reuse KlikxxiEngine.
 * RE dari com.lagradost.cloudxtream OploverzProvider.smali (mainUrl ambil dari
 * DramaidProvider.Companion.getBx08(), key "hc9" -> oploverz.am - cross-reference
 * antar provider yang berantakan di APK asli, bukan hierarki OOP bersih).
 * Domain diverifikasi live 20/10/2026: 2 sample (One Piece: Heroines, Naruto Kecil
 * Movie 3) dua2nya dapat embed valid dari .mobius>.mirror>option (blogger.com).
 */
class OploverzProvider : Kazefuri() {
    override var mainUrl = "https://oploverz.am"
    override var name = "Oploverz"
}
