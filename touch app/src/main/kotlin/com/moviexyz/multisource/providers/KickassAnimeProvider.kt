package com.moviexyz.multisource.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3U8Helper
import org.jsoup.nodes.Element

class KickassAnimeProvider : MainAPI() {
    override var mainUrl = "https://kickass-anime.ru"
    override var name = "KickassAnime"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime)
    
    override val mainPage = mainPageList {
        add(HomePageList("Latest Updates", listOf()))
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val items = document.select("div.anime-grid, .latest-updates").flatMap { container ->
            container.select("div.anime-item, .item").mapNotNull { item ->
                parseAnimeItem(item)
            }
        }
        
        return newHomePageResponse(
            listOf(HomePageList("Latest Updates", items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseAnimeItem(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h2, h3, .title") ?: return null
        val title = titleElement.text().trim()
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val poster = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src")
        
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        val fullPoster = if (poster?.startsWith("http") == true) poster else "$mainUrl$poster"
        
        return newAnimeSearchResponse(
            title,
            fullUrl,
            TvType.Anime,
            posterUrl = fullPoster
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.anime-item, .item").mapNotNull { item ->
            parseAnimeItem(item)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Handle the support popup mentioned in knowledge base
        document.select("div.popup-support, .support-message").forEach { popup ->
            popup.remove() // Remove support pop-ups from processing
        }
        
        val title = document.selectFirst("h1.title, .anime-title")?.text()?.trim() 
            ?: document.title().removeSuffix(" - KickassAnime").trim()
        
        val poster = document.selectFirst(".poster img, .thumb img")?.attr("src") 
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        val plot = document.selectFirst(".description, .plot")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
        
        val episodes = document.select(".episode-list .episode").map { ep ->
            val episodeNumber = ep.selectFirst(".episode-number")?.text()?.trim()?.toIntOrNull() ?: 1
            val episodeTitle = ep.selectFirst(".episode-title")?.text()?.trim() ?: "Episode $episodeNumber"
            val episodeUrl = ep.selectFirst("a")?.attr("href") ?: ""
            
            Episode(
                if (episodeUrl.startsWith("http")) episodeUrl else "$mainUrl$episodeUrl",
                episodeTitle,
                episodeNumber
            )
        }
        
        return newAnimeLoadResponse(
            title,
            url,
            TvType.Anime
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.episodes = listOf(
                newEpisode(episodes) {
                    this.name = "Episodes"
                    this.season = 1
                }
            )
        }
    }

    override suspend fun loadLinks(
         String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Handle support popup mentioned in knowledge base
        document.select("div.popup-support, .support-message").forEach { popup ->
            popup.remove()
        }
        
        val videoSources = mutableListOf<String>()
        
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("youtube") && !src.contains("ads")) {
                videoSources.add(if (src.startsWith("http")) src else "$mainUrl$src")
            }
        }
        
        document.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                videoSources.add(if (src.startsWith("http")) src else "$mainUrl$src")
            }
        }
        
        val scriptContent = document.select("script").text()
        val videoUrlPatterns = listOf(
            "file:\"([^\"]+)\"",
            "src:\"([^\"]+)\"",
            "url:\"([^\"]+)\"",
            "video_url:\"([^\"]+)\""
        )
        
        videoUrlPatterns.forEach { pattern ->
            val regex = Regex(pattern)
            regex.findAll(scriptContent).forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotEmpty() && (url.contains(".mp4") || url.contains(".m3u8"))) {
                    videoSources.add(if (url.startsWith("http")) url else "$mainUrl$url")
                }
            }
        }
        
        videoSources.forEach { source ->
            if (source.contains(".m3u8")) {
                M3U8Helper.generateM3u8(
                    name,
                    source,
                    "$mainUrl/"
                ).forEach(callback)
            } else if (source.contains(".mp4")) {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "Direct Stream",
                        source,
                        "$mainUrl/",
                        Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            } else {
                callback.invoke(
                    ExtractorLink(
                        name,
                        "Video Player",
                        source,
                        "$mainUrl/",
                        Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
            }
        }
        
        return videoSources.isNotEmpty()
    }
}