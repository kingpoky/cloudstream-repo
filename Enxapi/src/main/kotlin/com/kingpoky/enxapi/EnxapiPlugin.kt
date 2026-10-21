package com.kingpoky.enxapi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EnxapiPlugin : Plugin() {
    override fun load(context: Context) {
        // Satu modul, dua provider (pola Kazefuri): katalog donghua dan
        // katalog anime adalah API terpisah dengan jalur playback berbeda.
        registerMainAPI(Enxapi())
        registerMainAPI(EnxapiAnime())
    }
}
