package com.kingpoky.dubbindo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DubbindoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dubbindo())
    }
}
