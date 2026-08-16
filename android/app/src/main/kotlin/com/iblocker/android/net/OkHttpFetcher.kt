package com.iblocker.android.net

import com.iblocker.core.lists.FetchRequest
import com.iblocker.core.lists.FetchResult
import com.iblocker.core.lists.Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The list downloader's network layer; `core` only knows the [Fetcher] interface. */
object OkHttpFetcher : Fetcher {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun fetch(request: FetchRequest): FetchResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        client.newCall(builder.build()).execute().use { response ->
            FetchResult(
                statusCode = response.code,
                body = response.body?.bytes() ?: ByteArray(0),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
            )
        }
    }
}
