package com.kingpoky.kazefuri

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KazefuriPlugin : Plugin() {
    override fun load(context: Context) {
        // Family WP-anime-theme (mobius/mirror): 1 engine dasar + 3 turunan.
        registerMainAPI(Kazefuri())
        registerMainAPI(Nekokun())
        registerMainAPI(Auratail())
        registerMainAPI(OploverzProvider())
    }
}
