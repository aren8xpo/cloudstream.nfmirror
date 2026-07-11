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
        registerMainAPI(NetflixMirrorUltimate())
    }
}

class NetflixMirrorUltimate : MainAPI() {
    override var name = "Netflix Mirror Ultimate"
    override var mainUrl = "https://vidsrc.pm"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = mutableListOf<SearchResponse>()
        try {
            // Updated selectors to find trending items more reliably
            val response = app.get("$mainUrl/trending")
            val document = Jsoup.parse(response.text)
            
            // Look for any links that look like movies or shows
            document.select("a[href*='/movie/'], a[href*='/tv/'], .movie-card, .item").forEach { element ->
                val title = element.select(".title, .name, h3, span, b").first()?.text() 
                    ?: element.attr("title")
                    ?: element.text()
                
                val link = element.attr("href").ifEmpty { element.select("data-link").attr("data-link") }
                val poster = element.select("img").attr("data-src")
                    .ifEmpty { element.select("img").attr("src") }
                
                if (title.isNotEmpty() && link.isNotEmpty()) {
                    items.add(newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (e: Exception) {
            // Return empty list on failure rather than crashing
        }
        
        return newHomePageResponse("Trending Now", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val response = app.get("$mainUrl/search/$query")
            val document = Jsoup.parse(response.text)
            
            document.select("a[href*='/movie/'], a[href*='/tv/'], .item").forEach { element ->
                val title = element.select(".title, .name, h3").text().ifEmpty { element.text() }
                val link = element.attr("href")
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
        
        val title = document.select(".title, h1").first()?.text() ?: "Unknown Content"
        val plot = document.select(".description, .plot, #description").text()
        val poster = document.select(".poster img, .cover img").attr("src")
        
        val id = url.split("/").lastOrNull { it.isNotEmpty() } ?: url

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
        val sources = listOf("https://vidsrc.pm", "https://vidsrc.xyz", "https://2embed.cc")
        var found = false
        for (source in sources) {
            val currentUrl = "$source/embed/movie/$data"
            if (attemptExtraction(currentUrl, callback)) {
                found = true
            }
        }
        return found
    }

    private suspend fun attemptExtraction(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val randomUA = USER_AGENTS[Random.nextInt(USER_AGENTS.size)]
            val headers = mapOf("Referer" to url, "User-Agent" to randomUA)
            val response = app.get(url, headers = headers)

            val pattern = Pattern.compile("https://[^\"]+?\\.(?:m3u8|mp4)[^\"]*")
            val matcher = pattern.matcher(response.text)
            var internalFound = false
            while (matcher.find()) {
                val finalUrl = matcher.group().replace("\\/", "/")
                callback.invoke(newExtractorLink(
                    "Mirror Source",
                    "Mirror [${url.substringAfter("://").substringBefore("/")}]",
                    finalUrl,
                    type = if (finalUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                })
                internalFound = true
            }
            internalFound
        } catch (e: Exception) {
            false
        }
    }
}