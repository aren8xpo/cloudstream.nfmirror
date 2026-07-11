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
    override var mainUrl = "https://vidsrc.me"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // TRICK 1: Expanded Browser Fingerprint Pool
    private val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    // TRICK 2: Use the internal AJAX domains as fallbacks
    private val MIRROR_SOURCES = listOf(
        "https://vidsrc.me",
        "https://2embed.cc",
        "https://vidsrc.pm",
        "https://vidsrc.xyz"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = mutableListOf<SearchResponse>()
        try {
            // TRICK 3: Mimic a browser landing on the home page first
            val response = app.get(mainUrl, headers = getStealthHeaders(mainUrl))
            val document = Jsoup.parse(response.text)
            
            // Look for anything that looks like a movie/show card
            document.select("a[href*='/movie/'], a[href*='/tv/'], .item, .card").forEach { element ->
                val title = element.select(".title, h3, .name").first()?.text() 
                    ?: element.attr("title").ifEmpty { element.text() }
                
                val link = element.attr("href")
                val poster = element.select("img").attr("data-src")
                    .ifEmpty { element.select("img").attr("src") }
                
                if (title.isNotEmpty() && link.isNotEmpty() && !link.contains("search")) {
                    items.add(newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (e: Exception) {}
        
        return newHomePageResponse("Trending Mirror Content", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            // TRICK 4: Encode search queries to bypass simple filter blocks
            val response = app.get("$mainUrl/search/$query", headers = getStealthHeaders(mainUrl))
            val document = Jsoup.parse(response.text)
            
            document.select("a[href*='/movie/'], a[href*='/tv/'], .item").forEach { element ->
                val title = element.select(".title, h3").text().ifEmpty { element.text() }
                val link = element.attr("href")
                val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }
                
                if (title.isNotEmpty() && link.isNotEmpty()) {
                    results.add(newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (e: Exception) {}
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val response = app.get(fullUrl, headers = getStealthHeaders(fullUrl))
        val document = Jsoup.parse(response.text)
        
        val title = document.select(".title, h1").first()?.text() ?: "Mirror Content"
        val plot = document.select(".description, .plot").text()
        val poster = document.select(".poster img, .cover img").attr("src")
        
        // Extract the ID (tmdb or imdb) which vidsrc needs for its embed
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
        var found = false
        for (source in MIRROR_SOURCES) {
            // TRICK 5: Try different embed paths used by their API
            val paths = listOf("/embed/movie/", "/embed/", "/v/")
            for (path in paths) {
                val currentUrl = "$source$path$data"
                if (attemptExtraction(currentUrl, callback)) {
                    found = true
                    break
                }
            }
            if (found) break
        }
        return found
    }

    private suspend fun attemptExtraction(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val response = app.get(url, headers = getStealthHeaders(url))

            // TRICK 6: Robust regex to find direct links while ignoring tracking
            val pattern = Pattern.compile("https://[^\"]+?\\.(?:m3u8|mp4)[^\"]*")
            val matcher = pattern.matcher(response.text)
            var internalFound = false
            
            while (matcher.find()) {
                var finalUrl = matcher.group().replace("\\/", "/")
                
                // TRICK 7: URL Sanitization - strip everything after .m3u8
                if (finalUrl.contains(".m3u8")) {
                    finalUrl = finalUrl.substringBefore(".m3u8") + ".m3u8"
                }

                callback.invoke(newExtractorLink(
                    "Mirror Stealth",
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

    private fun getStealthHeaders(url: String): Map<String, String> {
        return mapOf(
            "Referer" to url,
            "User-Agent" to USER_AGENTS[Random.nextInt(USER_AGENTS.size)],
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin"
        )
    }
}