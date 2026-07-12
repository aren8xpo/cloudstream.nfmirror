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
    // Standard NetMirror API configuration
    override var name = "Netflix Mirror Ultimate"
    // Using the NetMirror-Extension's current working domains
    override var mainUrl = "https://net22.cc"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    private var cachedCookie: String = ""

    private fun getCookies(): Map<String, String> {
        return mapOf(
            "t_hash_t" to cachedCookie,
            "ott" to "nf",
            "hd" to "on"
        )
    }

    private fun getCommonHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "X-Requested-With" to "com.horis.cncverse"
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val items = mutableListOf<SearchResponse>()
        try {
            // First attempt: Check if we need to bypass
            if (cachedCookie.isEmpty()) {
                val response = app.post("https://net52.cc/verify.php", data = mapOf("g-recaptcha-response" to java.util.UUID.randomUUID().toString()))
                cachedCookie = response.headers["Set-Cookie"]?.substringAfter("t_hash_t=")?.substringBefore(";") ?: ""
            }

            val response = app.get("$mainUrl/mobile/home?app=1", cookies = getCookies(), headers = getCommonHeaders())
            val document = Jsoup.parse(response.text)

            document.select(".tray-container .item").forEach { element ->
                val title = element.select(".title, .name").text()
                val link = element.select("a").attr("href")
                val poster = element.select("img").attr("data-src").ifEmpty { element.select("img").attr("src") }

                if (title.isNotEmpty()) {
                    items.add(newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (e: Exception) {}

        return newHomePageResponse("Netflix Mirror Content", items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            // NetMirror uses a JSON search endpoint
            val response = app.get("$mainUrl/mobile/search.php?query=$query", cookies = getCookies(), headers = getCommonHeaders())
            // Note: In a real implementation, we would use .parsed<SearchData>()
            // For now, let's use a robust JSoup fallback if search returns HTML
            val document = Jsoup.parse(response.text)
            document.select(".item").forEach { element ->
                val title = element.select(".title").text()
                val id = element.select("a").attr("href")
                val poster = element.select("img").attr("src")
                if (title.isNotEmpty()) {
                    results.add(newMovieSearchResponse(title, id, TvType.Movie) {
                        this.posterUrl = poster
                    })
                }
            }
        } catch (e: Exception) {}
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get("$mainUrl/mobile/post.php?id=$url", cookies = getCookies(), headers = getCommonHeaders())
        val document = Jsoup.parse(response.text)

        val title = document.select(".title").text()
        val plot = document.select(".description").text()

        // NetMirror requires TMDB ID for links
        val tmdbId = document.select("data-tmdb").attr("data-tmdb").ifEmpty { url }

        return newMovieLoadResponse(title, url, TvType.Movie, tmdbId) {
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // NetMirror uses a special player API
        return try {
            val playerUrl = "https://net27.cc/newtv/player.php?id=$data"
            val response = app.get(playerUrl, headers = getCommonHeaders() + mapOf("Ott" to "nf"))

            // The response contains the video_link
            val videoLink = Pattern.compile("\"video_link\":\"(.*?)\"").matcher(response.text).let {
                if (it.find()) it.group(1).replace("\\/", "/") else null
            }

            if (videoLink != null) {
                callback.invoke(newExtractorLink(
                    "Netflix Mirror",
                    "NetMirror [HLS]",
                    videoLink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://net27.cc/"
                })
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }
}