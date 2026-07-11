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
// Data classes
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
// Provider
// ---------------------------------------------------------------------------

class NetflixMirrorUltimate : MainAPI() {
    override var name = "Netflix Mirror Ultimate"
    override var mainUrl = "https://net27.cc"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // Convert a MovieItem into a CloudStream SearchResponse
    private fun MovieItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            name  = title,
            url   = tmdbId.toString(),
            type  = TvType.Movie
        ) {
            posterUrl = poster
        }
    }

    // ---------------------------------------------------------------------------
    // getMainPage  →  GET /api/catalog/curated/trending
    // ---------------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val catalog = app.get("$mainUrl/api/catalog/curated/trending")
            .parsed<CatalogResponse>()

        val homePageLists = catalog.rails.map { rail ->
            HomePageList(
                name  = rail.title,
                list  = rail.items.map { it.toSearchResponse() },
                isHorizontalImages = true
            )
        }

        return HomePageResponse(homePageLists)
    }

    // ---------------------------------------------------------------------------
    // search  →  GET /api/search?q=QUERY&page=1
    // ---------------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val result = app.get("$mainUrl/api/search?q=${query.encodeUri()}&page=1")
            .parsed<SearchResponseJson>()

        return result.items.map { it.toSearchResponse() }
    }

    // ---------------------------------------------------------------------------
    // load  →  GET /api/embed-tmdb/{tmdbId}
    // ---------------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val embed = app.get("$mainUrl/api/embed-tmdb/$url")
            .parsed<EmbedResponse>()

        return newMovieLoadResponse(
            name    = embed.title,
            url     = embed.tmdbId.toString(),
            type    = TvType.Movie,
            dataUrl = embed.tmdbId.toString()
        ) {
            posterUrl = embed.poster
            year      = embed.year?.toIntOrNull()
        }
    }

    // ---------------------------------------------------------------------------
    // loadLinks  →  iterate embed.streams, one ExtractorLink per stream
    // ---------------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = app.get("$mainUrl/api/embed-tmdb/$data")
            .parsed<EmbedResponse>()

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
                    this.isM3u8  = false
                }
            )
        }

        return true
    }
}
