package com.kingpoky.pencuri

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PencuriPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pencuri())
    }
}
