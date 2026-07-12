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
    val resolution: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EmbedResponse(
    val tmdbId: Int = 0,
    val title: String = "",
    val poster: String? = null,
    val year: String? = null,
    val type: String? = null,
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

    private fun MovieItem.toSearchResponse(): SearchResponse {
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, tmdbId.toString(), TvType.TvSeries) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, tmdbId.toString(), TvType.Movie) {
                posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalog = app.get("$mainUrl/api/catalog/curated/trending")
            .parsedSafe<CatalogResponse>() ?: return null

        val homePageLists = catalog.rails.map { rail ->
            HomePageList(
                name  = rail.title,
                list  = rail.items.map { it.toSearchResponse() },
                isHorizontalImages = true
            )
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val result = app.get("$mainUrl/api/catalog/search?q=$encodedQuery")
            .parsedSafe<SearchResponseJson>() ?: return emptyList()

        return result.items.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val details = try {
            app.get("$mainUrl/api/catalog/title/movie/$url").parsedSafe<MovieItem>()
                ?: app.get("$mainUrl/api/catalog/title/tv/$url").parsedSafe<MovieItem>()
        } catch (e: Exception) {
            null
        } ?: throw ErrorLoadingException("Content not found")

        val isTv = details.type == "tv"
        
        return if (isTv) {
            val episodes = listOf(newEpisode(url) { 
                this.name = "Play"
                this.season = 1
                this.episode = 1 
            })
            newTvSeriesLoadResponse(details.title, details.tmdbId.toString(), TvType.TvSeries, episodes) {
                posterUrl = details.poster
                year      = details.year?.toIntOrNull()
                plot      = details.overview
            }
        } else {
            newMovieLoadResponse(details.title, details.tmdbId.toString(), TvType.Movie, details.tmdbId.toString()) {
                posterUrl = details.poster
                year      = details.year?.toIntOrNull()
                plot      = details.overview
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = app.get("$mainUrl/api/embed-tmdb/$data?type=movie").parsedSafe<EmbedResponse>()
            ?: app.get("$mainUrl/api/embed-tmdb/$data?type=tv&se=1&ep=1").parsedSafe<EmbedResponse>()
            ?: return false

        if (embed.streams.isEmpty()) return false

        embed.streams.forEach { stream ->
            callback.invoke(
                newExtractorLink(
                    source  = "NetMirror",
                    name    = "NetMirror ${stream.resolution ?: ""}p".trim(),
                    url     = stream.url,
                    type    = ExtractorLinkType.VIDEO
                ) {
                    this.quality = stream.resolution ?: Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                }
            )
        }

        return true
    }
}
