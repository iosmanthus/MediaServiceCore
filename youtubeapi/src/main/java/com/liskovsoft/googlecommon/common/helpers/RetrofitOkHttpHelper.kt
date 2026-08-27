package com.liskovsoft.googlecommon.common.helpers

import com.google.net.cronet.okhttptransport.CronetInterceptor
import com.liskovsoft.sharedutils.cronet.CronetManager
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.youtubeapi.common.helpers.AppConstants
import com.liskovsoft.youtubeapi.app.AppService
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal object RetrofitOkHttpHelper {
    private val authSkipList = mutableListOf<Request>()
    // NOTE: request identity is unreliable here - upstream interceptors may rebuild the object
    // before ours sees it - so flag the calling thread instead.
    private val cookieAuthThread = ThreadLocal<Boolean>()

    @JvmStatic
    val authHeaders = mutableMapOf<String, String>()

    @JvmStatic
    val client: OkHttpClient by lazy { createClient() }

    @JvmStatic
    var disableCompression: Boolean = false

    @JvmStatic
    fun addAuthSkip(request: Request) {
        if (!authSkipList.contains(request))
            authSkipList.add(request)
    }

    /** Authorize calls made on this thread the way a signed-in browser does. See CookieAuthStore. */
    @JvmStatic
    fun setCookieAuth(enabled: Boolean) {
        cookieAuthThread.set(if (enabled) true else null)
    }

    private val commonHeaders = mapOf(
        // Enable compression in production
        "Accept-Encoding" to DefaultHeaders.ACCEPT_ENCODING,
    )

    private val apiHeaders = mapOf(
        "User-Agent" to DefaultHeaders.APP_USER_AGENT,
        "Referer" to DefaultHeaders.REFERER
    )

    private val apiPrefixes = arrayOf(
        "https://www.googleapis.com/upload/drive/v3",
        "https://www.googleapis.com/drive/v3",
        "https://m.youtube.com/youtubei/v1/",
        "https://www.youtube.com/youtubei/v1/",
        "https://youtubei.googleapis.com/youtubei/v1",
        "https://www.youtube.com/api/stats/",
        "https://clients1.google.com/complete/"
    )

    /**
     * NOTE: visitor header could broke many apis. E.g. VisitorService
     */
    private val visitorApiSuffixes = arrayOf(
        "/youtubei/v1/browse",
        "/youtubei/v1/search",
        "/youtubei/v1/player",
        "/youtubei/v1/reel/",
        "/youtubei/v1/next",
        "/api/stats/",
    )

    private val tParamSuffixes = listOf("/browse", "/next", "/reel", "/playlist")

    private fun createClient(): OkHttpClient {
        val builder = OkHttpManager.instance().client.newBuilder()
        addCommonHeaders(builder)
        //addCronetInterceptor(builder)
        return builder.build()
    }

    private fun addCommonHeaders(builder: OkHttpClient.Builder) {
        builder.addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers()
            val requestBuilder = request.newBuilder()

            applyHeaders(this.commonHeaders, headers, requestBuilder)

            val url = request.url().toString()

            if (apiPrefixes.any { url.startsWith(it) }) {
                val doSkipAuth = authSkipList.remove(request)

                // Empty Home fix (anonymous user) and improve Recommendations for everyone
                // Under cookie auth the browser session owns the visitorData; injecting ours
                // gets the request rejected. See yuliskov/SmartTube#6030.
                if (visitorApiSuffixes.any { url.contains(it) } &&
                        !(cookieAuthThread.get() == true && com.liskovsoft.youtubeapi.app.CookieAuthStore.isEnabled()))
                    headers["X-Goog-Visitor-Id"] ?: AppService.instance().visitorData?.let { requestBuilder.header("X-Goog-Visitor-Id", it) }

                applyHeaders(this.apiHeaders, headers, requestBuilder)

                val tParam = if (tParamSuffixes.any { url.contains(it) }) YouTubeHelper.generateTParameter() else null

                val useCookieAuth = cookieAuthThread.get() == true &&
                        com.liskovsoft.youtubeapi.app.CookieAuthStore.isEnabled()
                if (useCookieAuth) {
                    applyQueryKeys(mapOf("prettyPrint" to "false", "t" to tParam), request, requestBuilder)
                    applyHeaders(com.liskovsoft.youtubeapi.app.CookieAuthStore.authHeaders(), headers, requestBuilder)
                } else if (authHeaders.isEmpty() || doSkipAuth) {
                    applyQueryKeys(mapOf("key" to AppConstants.API_KEY, "prettyPrint" to "false", "t" to tParam),
                        request, requestBuilder)
                } else {
                    applyQueryKeys(mapOf("prettyPrint" to "false", "t" to tParam), request, requestBuilder)
                    applyHeaders(authHeaders, headers, requestBuilder)
                }
            }

            chain.proceed(requestBuilder.build())
        }
    }

    private fun applyHeaders(newHeaders: Map<String, String?>, oldHeaders: Headers, builder: Request.Builder) {
        for (header in newHeaders) {
            if (disableCompression && header.key == "Accept-Encoding") {
                continue
            }

            // Don't override existing headers
            oldHeaders[header.key] ?: header.value?.let { builder.header(header.key, it) } // NOTE: don't remove null check
        }
    }

    private fun applyQueryKeys(keys: Map<String, String?>, request: Request, builder: Request.Builder) {
        val originUrl = request.url()

        var newUrlBuilder: HttpUrl.Builder? = null

        for (entry in keys) {
            // Don't override existing keys
            originUrl.queryParameter(entry.key) ?: run {
                if (entry.value == null)
                    return@run

                if (newUrlBuilder == null) {
                    newUrlBuilder = originUrl.newBuilder()
                }

                newUrlBuilder?.addQueryParameter(entry.key, entry.value)
            }
        }

        newUrlBuilder?.run {
            builder.url(build())
        }
    }

    private fun addCronetInterceptor(builder: OkHttpClient.Builder) {
        val engine = CronetManager.getEngine(AppService.instance().context)
        if (engine != null) {
            builder.addInterceptor(CronetInterceptor.newBuilder(engine).build())
        }
    }
}