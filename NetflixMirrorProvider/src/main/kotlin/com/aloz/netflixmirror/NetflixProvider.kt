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

    // Standard headers for all API calls
    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    )

    // Helper to convert internal MovieItem to Cloudstream SearchResponse
    private fun MovieItem.toSearchResponse(): SearchResponse {
        val mediaType = if (type == "tv") "tv" else "movie"
        // Ensure path always has the format "type/tmdbId"
        val dataPath = "$mediaType/$tmdbId"
        
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, dataPath, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = this@toSearchResponse.year?.toIntOrNull()
            }
        } else {
            newMovieSearchResponse(title, dataPath, TvType.Movie) {
                this.posterUrl = poster
                this.year = this@toSearchResponse.year?.toIntOrNull()
            }
        }
    }

    /**
     * Fetches the home page rails (Trending, Top 10, etc.)
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalog = app.get("$mainUrl/api/catalog/curated/trending", headers = apiHeaders)
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
        val result = app.get("$mainUrl/api/catalog/search?q=$encodedQuery", headers = apiHeaders)
            .parsedSafe<SearchResponseJson>() ?: return emptyList()

        return result.items.map { it.toSearchResponse() }
    }

    /**
     * Loads detailed info for a specific title
     */
    override suspend fun load(url: String): LoadResponse {
        // Clean URL to handle potential absolute paths or raw IDs
        val path = url.substringAfter("https://net27.cc/").substringAfter("http://net27.cc/").removePrefix("/")
        
        // Split path into [type, id]
        val segments = path.split("/")
        
        // Fix for the logcat error: "Invalid content path: 83533"
        // If there's no type prefix, we default to movie and use the whole string as ID
        val mediaType = if (segments.size >= 2) segments[0] else "movie"
        val tmdbId = if (segments.size >= 2) segments[1] else path

        if (tmdbId.isBlank()) throw ErrorLoadingException("Empty content ID")

        val details = app.get("$mainUrl/api/catalog/title/$mediaType/$tmdbId", headers = apiHeaders)
            .parsedSafe<MovieItem>() ?: throw ErrorLoadingException("Content metadata not found for $mediaType/$tmdbId")

        return if (mediaType == "tv") {
            val episodes = listOf(
                newEpisode("$tmdbId?type=tv") {
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
            newMovieLoadResponse(details.title, url, TvType.Movie, "$tmdbId?type=movie") {
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
        val embed = app.get("$mainUrl/api/embed-tmdb/$data", headers = apiHeaders)
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
                        // Fix for 403 Forbidden: Use the exact URL the server expects
                        this.referer = "https://h5.aoneroom.com/"
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
                    // Fix for 403 Forbidden: Use the exact URL the server expects
                    this.referer = "https://h5.aoneroom.com/"
                }
            )
            foundAny = true
        }

        return foundAny
    }
}
