package com.moviexyz.multisource

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@Plugin
class MultiSourcePlugin : BasePlugin() {
    override fun load(context: Context) {
        // Register all providers
        registerMainAPI(MusicHQProvider())
        registerMainAPI(MovieMazeProvider())
        registerMainAPI(KickassAnimeProvider())
        registerMainAPI(MirrorSiteProvider())
    }
}