package com.aloz.netflixmirror

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.util.regex.Pattern
import kotlin.random.Random

@CloudstreamPlugin
class NetflixMirrorUltimatePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        // All providers should be registered in the load function
        registerMainAPI(NetflixMirrorUltimate())
    }
}

class NetflixMirrorUltimate : MainAPI() {
    override var name = "Netflix Mirror Ultimate"
    override var mainUrl = "https://vidsrc.to"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val MIRROR_SOURCES = listOf(
        "https://vidsrc.to",
        "https://vidsrc.me",
        "https://2embed.me"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = mutableListOf<SearchResponse>()
        try {
            // Using a more generic search for "popular" content if trending is blocked
            val response = app.get("$mainUrl/trending")
            val document = Jsoup.parse(response.text)
            
            // Fixed selectors for common mirror site layouts
            document.select(".movie-item, .item, .card").forEach { element ->
                val title = element.select(".title, .name, h3").text()
                val link = element.select("a").attr("href")
                val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                
                if (title.isNotEmpty() && link.isNotEmpty()) {
                    items.add(newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (e: Exception) {
            // Log error to app logs
        }
        
        return newHomePageResponse("Trending Now", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val response = app.get("$mainUrl/search/$query")
            val document = Jsoup.parse(response.text)
            
            document.select(".movie-item, .item, .card").forEach { element ->
                val title = element.select(".title, .name, h3").text()
                val link = element.select("a").attr("href")
                val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                
                if (title.isNotEmpty() && link.isNotEmpty()) {
                    results.add(newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (e: Exception) {
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = app.get(fullUrl)
        val document = Jsoup.parse(response.text)
        
        val title = document.select(".title, h1").text().ifEmpty { "Unknown Title" }
        val plot = document.select(".description, .plot, #description").text()
        val poster = document.select(".poster img, .cover img").attr("src")
        
        // Extract ID for the mirror embeds
        val id = if (url.contains("-")) url.substringAfterLast("-") else url.substringAfterLast("/")

        return newMovieLoadResponse(title, url, TvType.Movie, id) {
            this.plot = plot
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        for (source in MIRROR_SOURCES) {
            val currentUrl = if (data.contains("tt")) {
                 "$source/embed/movie/$data"
            } else {
                 "$source/embed/$data"
            }
            
            val result = attemptExtraction(currentUrl)
            if (result != null) {
                callback.invoke(result)
                found = true
            }
        }
        return found
    }

    private suspend fun attemptExtraction(url: String): ExtractorLink? {
        try {
            val randomUA = USER_AGENTS[Random.nextInt(USER_AGENTS.size)]
            val headers = mapOf("Referer" to url, "User-Agent" to randomUA)
            val response = app.get(url, headers = headers)

            // Look for m3u8 or mp4 links in the script or iframe tags
            val pattern = Pattern.compile("https://[^\"]+?\\.(?:m3u8|mp4)[^\"]*")
            val matcher = pattern.matcher(response.text)
            if (matcher.find()) {
                val finalUrl = matcher.group().replace("\\/", "/")
                return newExtractorLink(
                    "Source",
                    "Mirror [${url.substringAfter("://").substringBefore("/")}]",
                    finalUrl,
                    type = if (finalUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}