package com.kingpoky.kazefuri

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KazefuriPlugin : Plugin() {
    override fun load(context: Context) {
        // Family WP-anime-theme (mobius/mirror): 1 engine dasar + 2 turunan.
        // Oploverz DICABUT 23/10/2026: oploverz.am kehilangan CPT `anime`,
        // semua /anime/<slug>/ balas 404 sehingga load() putus total.
        registerMainAPI(Kazefuri())
        registerMainAPI(Nekokun())
        registerMainAPI(Auratail())
    }
}
