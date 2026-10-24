package com.kingpoky.dramabox

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramaboxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dramabox())
    }
}
