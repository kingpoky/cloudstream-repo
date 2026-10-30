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
        // watch.asiastream.cc (pemutar film NodrakorId) tidak dikenal
        // extractor bawaan APK — tanpa baris ini loadExtractor() melewatinya
        // diam-diam dan film tampil "tidak ada server". Lihat AsiaStreamExtractor.
        registerExtractorAPI(AsiaStreamExtractor())
    }
}
