package com.aloz.netflixmirror

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Main Plugin class required by Cloudstream to load the provider.
 */
@CloudstreamPlugin
class NetflixMirrorUltimatePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        // Registering the provider so it appears in the app's sources
        registerMainAPI(NetflixMirrorUltimate())
    }
}

// ---------------------------------------------------------------------------
// Data classes for the Net27 REST API
// These match the observed JSON structure from the site's network traffic.
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovieItem(
    val tmdbId: Int = 0,
    val title: String = "",
    val poster: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val year: String? = null,
    val type: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Rail(
    val title: String = "",
    val items: List<MovieItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogResponse(
    val rails: List<Rail> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchResponseJson(
    val items: List<MovieItem> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Stream(
    val url: String = "",
    val resolution: Int? = null,
    val size: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbedResponse(
    val tmdbId: Int = 0,
    val title: String = "",
    val mp4: String? = null,
    val resolution: String? = null,
    val streams: List<Stream> = emptyList()
)

// ---------------------------------------------------------------------------
// Provider Implementation
// ---------------------------------------------------------------------------

class NetflixMirrorUltimate : MainAPI() {
    override var name = "Netflix Mirror Ultimate"
    override var mainUrl = "https://net27.cc"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // Helper to convert internal MovieItem to Cloudstream SearchResponse
    private fun MovieItem.toSearchResponse(): SearchResponse {
        val mediaType = if (type == "tv") TvType.TvSeries else TvType.Movie
        // Include type in path so load() knows which endpoint to call
        val path = if (type == "tv") "tv/$tmdbId" else "movie/$tmdbId"
        
        return if (mediaType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, path, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = this@toSearchResponse.year?.toIntOrNull()
            }
        } else {
            newMovieSearchResponse(title, path, TvType.Movie) {
                this.posterUrl = poster
                this.year = this@toSearchResponse.year?.toIntOrNull()
            }
        }
    }

    /**
     * Fetches the home page rails (Trending, Top 10, etc.)
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalog = app.get("$mainUrl/api/catalog/curated/trending")
            .parsedSafe<CatalogResponse>() ?: return null

        val lists = catalog.rails
            .filter { it.items.isNotEmpty() }
            .map { rail ->
                HomePageList(
                    name = rail.title,
                    list = rail.items.map { it.toSearchResponse() },
                    isHorizontalImages = true
                )
            }

        return newHomePageResponse(lists)
    }

    /**
     * Searches the catalog via the REST API
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val result = app.get("$mainUrl/api/catalog/search?q=$encodedQuery")
            .parsedSafe<SearchResponseJson>() ?: return emptyList()

        return result.items.map { it.toSearchResponse() }
    }

    /**
     * Loads detailed info for a specific title
     */
    override suspend fun load(url: String): LoadResponse {
        val segments = url.split("/")
        if (segments.size < 2) throw ErrorLoadingException("Invalid URL structure: $url")
        
        val mediaType = segments[0]
        val tmdbId = segments[1]

        val details = app.get("$mainUrl/api/catalog/title/$mediaType/$tmdbId")
            .parsedSafe<MovieItem>() ?: throw ErrorLoadingException("Content metadata not found")

        return if (mediaType == "tv") {
            // Simplified: creates a "Play" episode for TV shows to trigger extraction
            val episodes = listOf(
                newEpisode(tmdbId) {
                    this.name = details.title
                    this.season = 1
                    this.episode = 1
                }
            )
            newTvSeriesLoadResponse(details.title, url, TvType.TvSeries, episodes) {
                this.posterUrl = details.poster
                this.year = details.year?.toIntOrNull()
                this.plot = details.overview
            }
        } else {
            newMovieLoadResponse(details.title, url, TvType.Movie, tmdbId) {
                this.posterUrl = details.poster
                this.year = details.year?.toIntOrNull()
                this.plot = details.overview
            }
        }
    }

    /**
     * Fetches the actual video streaming links
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Fetch embed data which contains direct MP4 or M3U8 links
        val embed = app.get("$mainUrl/api/embed-tmdb/$data")
            .parsedSafe<EmbedResponse>() ?: return false

        var foundAny = false

        // 1. Check direct stream list
        embed.streams.forEach { stream ->
            if (stream.url.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "NetMirror",
                        name = "NetMirror ${stream.resolution ?: "?"}p",
                        url = stream.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = stream.resolution ?: Qualities.Unknown.value
                        this.referer = "$mainUrl/"
                    }
                )
                foundAny = true
            }
        }

        // 2. Fallback to main MP4 link if streams list is empty
        if (!foundAny && !embed.mp4.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = "NetMirror",
                    name = "NetMirror ${embed.resolution ?: "?"}p",
                    url = embed.mp4!!,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = embed.resolution?.toIntOrNull() ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
            foundAny = true
        }

        return foundAny
    }
}
