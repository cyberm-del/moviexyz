package com.moviexyz.multisource.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3U8Helper
import org.jsoup.nodes.Element

class MirrorSiteProvider : MainAPI() {
    override var mainUrl = "https://a.111477.xyz"
    override var name = "MirrorSite"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    
    override val mainPage = mainPageList {
        add(HomePageList("Top Movies", listOf()))
        add(HomePageList("New Releases", listOf()))
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/").document
        
        val items = document.select("div.movie-list, .content").flatMap { container ->
            container.select("div.movie-item, .item").mapNotNull { item ->
                parseItem(item)
            }
        }
        
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun parseItem(element: Element): SearchResponse? {
        val titleElement = element.selectFirst("h2, h3, .title") ?: return null
        val title = titleElement.text().trim()
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val poster = element.selectFirst("img")?.attr("src") ?: element.selectFirst("img")?.attr("data-src")
        
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        val fullPoster = if (poster?.startsWith("http") == true) poster else "$mainUrl$poster"
        
        val isAnime = element.select(".anime, .anime-tag").isNotEmpty()
        val isSeries = element.select(".tv, .tv-series, .series").isNotEmpty()
        
        return if (isAnime) {
            newAnimeSearchResponse(
                title,
                fullUrl,
                TvType.Anime,
                posterUrl = fullPoster
            )
        } else if (isSeries) {
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
            parseItem(item)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title, .movie-title")?.text()?.trim() 
            ?: document.title().removeSuffix(" - MirrorSite").trim()
        
        val poster = document.selectFirst(".poster img, .thumbnail img")?.attr("src")
            ?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
        
        val plot = document.selectFirst(".description, .summary")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
        
        val year = document.selectFirst(".year, .release-year")?.text()?.trim()?.toIntOrNull()
        
        val isAnime = document.select("div.anime-info, .anime-content").isNotEmpty()
        val isSeries = document.select("div.seasons, .episodes-list").isNotEmpty()
        
        if (isAnime || isSeries) {
            val episodes = document.select("div.season-block, .episode-list").flatMap { seasonBlock ->
                val seasonNumber = seasonBlock.selectFirst("h3.season-title, .season-name")?.text()
                    ?.replace(Regex("Season|\\D"), "")?.trim()?.toIntOrNull() ?: 1
                
                seasonBlock.select("div.episode, .episode-item").map { ep ->
                    val episodeNumber = ep.selectFirst(".episode-number, .num")?.text()
                        ?.replace(Regex("\\D"), "")?.trim()?.toIntOrNull() ?: 1
                    val episodeTitle = ep.selectFirst("h4.episode-title, .name")?.text()?.trim()
                        ?: "Episode $episodeNumber"
                    val episodeUrl = ep.selectFirst("a.episode-link, a")?.attr("href") ?: ""
                    
                    Episode(
                        if (episodeUrl.startsWith("http")) episodeUrl else "$mainUrl$episodeUrl",
                        episodeTitle,
                        episodeNumber,
                        seasonNumber
                    )
                }
            }
            
            if (isAnime) {
                return newAnimeLoadResponse(
                    title,
                    url,
                    TvType.Anime
                ) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.episodes = listOf(
                        newEpisode(episodes) {
                            this.name = "Episodes"
                            this.season = 1
                        }
                    )
                }
            } else {
                return newTvSeriesLoadResponse(
                    title,
                    url,
                    TvType.TvSeries,
                    episodes
                ) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                }
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
            if (src.isNotEmpty() && !src.contains("youtube") && !src.contains("ads") && !src.contains("google")) {
                videoSources.add(if (src.startsWith("http")) src else "$mainUrl$src")
            }
        }
        
        document.select("script").forEach { script ->
            val scriptData = script.data()
            
            val m3u8Regex = Regex("""["']?file["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            m3u8Regex.findAll(scriptData).forEach { match ->
                val m3u8Url = match.groupValues[1]
                if (m3u8Url.isNotEmpty()) {
                    M3U8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        "$mainUrl/"
                    ).forEach(callback)
                }
            }
            
            val mp4Regex = Regex("""["']?file["']?\s*:\s*["']([^"']+\.mp4[^"']*)["']""")
            mp4Regex.findAll(scriptData).forEach { match ->
                val mp4Url = match.groupValues[1]
                if (mp4Url.isNotEmpty()) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "Direct Stream",
                            mp4Url,
                            "$mainUrl/",
                            Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                }
            }
            
            val srcRegex = Regex("""["']?src["']?\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            srcRegex.findAll(scriptData).forEach { match ->
                val srcUrl = match.groupValues[1]
                if (srcUrl.isNotEmpty()) {
                    if (srcUrl.contains(".m3u8")) {
                        M3U8Helper.generateM3u8(
                            name,
                            srcUrl,
                            "$mainUrl/"
                        ).forEach(callback)
                    } else {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "Video Source",
                                srcUrl,
                                "$mainUrl/",
                                Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
            }
        }
        
        document.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotEmpty()) {
                if (src.contains(".m3u8")) {
                    M3U8Helper.generateM3u8(
                        name,
                        src,
                        "$mainUrl/"
                    ).forEach(callback)
                } else {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "Direct Video",
                            src,
                            "$mainUrl/",
                            Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                }
            }
        }
        
        return true
    }
}