package com.aloz.netflixmirror

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink

@CloudstreamPlugin
class NetflixMirrorUltimatePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(NetflixMirrorUltimate())
    }
}

// ---------------------------------------------------------------------------
// Data classes for the new REST API
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class MovieItem(
    val tmdbId: Int = 0,
    val title: String = "",
    val poster: String? = null,
    val overview: String? = null,
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
// Provider using the new REST API
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

    private fun MovieItem.toSearchResponse(): SearchResponse {
        val mediaType = if (type == "tv") "tv" else "movie"
        // Store path as a unique string the load function can parse
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

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val result = app.get("$mainUrl/api/catalog/search?q=$encodedQuery", headers = apiHeaders)
            .parsedSafe<SearchResponseJson>() ?: return emptyList()

        return result.items.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        // Handle full URLs if Cloudstream absolute-ifies them
        val path = url.substringAfter("https://net27.cc/").substringAfter("http://net27.cc/").substringAfter("/")
        val segments = path.split("/")
        
        if (segments.size < 2) throw ErrorLoadingException("Invalid content path: $path")
        
        val mediaType = segments[0]
        val tmdbId = segments[1]

        val details = app.get("$mainUrl/api/catalog/title/$mediaType/$tmdbId", headers = apiHeaders)
            .parsedSafe<MovieItem>() ?: throw ErrorLoadingException("Content metadata not found")

        return if (mediaType == "tv") {
            // Standard episode structure for TV shows
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data now contains the query parameters (e.g. "12345?type=movie")
        val embed = app.get("$mainUrl/api/embed-tmdb/$data", headers = apiHeaders)
            .parsedSafe<EmbedResponse>() ?: return false

        var foundAny = false

        // 1. Process the streams array
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

        // 2. Fallback to direct MP4 if available
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
