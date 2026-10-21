package com.kingpoky.klikxxi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class KlikxxiEnginePlugin : Plugin() {
    override fun load(context: Context) {
        // Family muvipro (KlikxxiEngine): 2 turunan live-verified.
        registerMainAPI(KawanFilm())
        registerMainAPI(NodrakorId())
    }
}
