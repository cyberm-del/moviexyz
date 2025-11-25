package com.moviexyz.multisource.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3U8Helper
import org.jsoup.nodes.Element

class MusicHQProvider : MainAPI() {
    override var mainUrl = "https://musichq.cc"
    override var name = "MusicHQ"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    
    override val mainPage = mainPageList {
        add(HomePageList("Latest Movies", listOf()))
        add(HomePageList("Recommended", listOf()))
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/").document
        
        val items = document.select("div.movie-grid, div.featured-section").flatMap { container ->
            container.select("div.movie-item, .item").mapNotNull { item ->
                parseMovieItem(item)
            }
        }
        
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseMovieItem(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h2, h3, .title") ?: return null
        val title = titleElement.text().trim()
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val poster = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src")
        
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        val fullPoster = if (poster?.startsWith("http") == true) poster else "$mainUrl$poster"
        
        val isSeries = element.select(".season, .episode, .tv-series").size > 0
        
        return if (isSeries) {
            newTvSeriesSearchResponse(
                title,
                fullUrl,
                TvType.TvSeries,
                posterUrl = fullPoster
            )
        } else {
            newMovieSearchResponse(
                title,
                fullUrl,
                TvType.Movie,
                posterUrl = fullPoster
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movie-item, .item").mapNotNull { item ->
            parseMovieItem(item)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title, .movie-title")?.text()?.trim() 
            ?: document.title().removeSuffix(" - MusicHQ").trim()
        
        val poster = document.selectFirst(".poster img, .thumb img")?.attr("src") 
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        val plot = document.selectFirst(".description, .plot")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
        
        val year = document.selectFirst(".year, .date")?.text()?.trim()?.toIntOrNull()
        val rating = document.selectFirst(".rating, .imdb")?.text()?.trim()
        
        val isSeries = document.select(".seasons, .episodes, .season-list").size > 0
        
        if (isSeries) {
            val seasons = document.select(".season-item, .season-block").map { seasonElement ->
                val seasonNumber = seasonElement.selectFirst(".season-number")?.text()?.replace("Season", "")?.trim()?.toIntOrNull() ?: 1
                val seasonName = seasonElement.selectFirst(".season-title")?.text()?.trim() ?: "Season $seasonNumber"
                
                val episodes = seasonElement.select(".episode-item, .episode").map { episodeElement ->
                    val episodeNumber = episodeElement.selectFirst(".episode-number")?.text()?.trim()?.toIntOrNull() ?: 1
                    val episodeTitle = episodeElement.selectFirst(".episode-title")?.text()?.trim() ?: "Episode $episodeNumber"
                    val episodeUrl = episodeElement.selectFirst("a")?.attr("href") ?: return@map null
                    
                    val fullEpisodeUrl = if (episodeUrl.startsWith("http")) episodeUrl else "$mainUrl$episodeUrl"
                    
                    Episode(
                        fullEpisodeUrl,
                        episodeTitle,
                        episodeNumber,
                        seasonNumber
                    )
                }.filterNotNull()
                
                newSeason(seasonNumber, seasonName, episodes)
            }.filter { it.episodes.isNotEmpty() }
            
            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                seasons
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.rating = rating?.toFloatOrNull()
                this.tags = document.select(".genre, .category").map { it.text().trim() }
            }
        } else {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.rating = rating?.toFloatOrNull()
                this.tags = document.select(".genre, .category").map { it.text().trim() }
            }
        }
    }

    override suspend fun loadLinks(
         String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
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
                        "Direct MP4",
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
                        "Embed Source",
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