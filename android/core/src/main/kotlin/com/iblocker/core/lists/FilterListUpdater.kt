package com.iblocker.core.lists

import com.iblocker.core.rules.CompiledBlocklist
import com.iblocker.core.rules.SeedRules
import com.iblocker.core.shared.AppPaths
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class FetchRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val timeoutSeconds: Int = 60,
)

data class FetchResult(
    val statusCode: Int,
    val body: ByteArray,
    val etag: String? = null,
    val lastModified: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is FetchResult &&
            statusCode == other.statusCode &&
            body.contentEquals(other.body) &&
            etag == other.etag &&
            lastModified == other.lastModified

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + body.contentHashCode()
        result = 31 * result + (etag?.hashCode() ?: 0)
        result = 31 * result + (lastModified?.hashCode() ?: 0)
        return result
    }
}

/** The network layer, injected so tests never touch the wire. */
fun interface Fetcher {
    suspend fun fetch(request: FetchRequest): FetchResult
}

data class UpdateSummary(
    val updatedSourceIDs: MutableList<String> = ArrayList(),
    val unchangedSourceIDs: MutableList<String> = ArrayList(),
    val failedSourceIDs: MutableMap<String, String> = LinkedHashMap(),
) {
    val anyChanged: Boolean get() = updatedSourceIDs.isNotEmpty()
}

/**
 * Failures we raise ourselves, phrased for the list row a user actually
 * reads rather than for a log.
 */
sealed class FilterListUpdateException(val userMessage: String) : Exception(userMessage) {
    class HttpStatus(val code: Int) : FilterListUpdateException(
        when (code) {
            404 -> "Not found (404) — the list may have moved"
            403 -> "Access denied (403)"
            429 -> "Rate limited — try again later"
            in 500..599 -> "Server error ($code)"
            else -> "Unexpected response ($code)"
        }
    )

    class EmptyBody : FilterListUpdateException("Server returned an empty file")
}

/** Downloads raw list texts into the on-device cache with conditional GETs. */
class FilterListUpdater(
    private val paths: AppPaths,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetcher: Fetcher,
) {

    /**
     * Fetches every enabled source, updating `state.metadata` and the cached
     * list files. Does not recompile; call [BlocklistCompiler] after.
     */
    suspend fun update(state: FilterListState, force: Boolean = false): UpdateSummary {
        val summary = UpdateSummary()
        paths.ensureDirectories()

        for (source in state.sources.filter { it.enabled }) {
            // Bundled rulesets ship in the binary — nothing to fetch.
            if (SeedRules.bundledText(source.id) != null) continue

            var metadata = state.metadata[source.id] ?: FilterListMetadata()
            val headers = LinkedHashMap<String, String>()
            val cacheFile = paths.cachedListFile(source.id)
            if (!force && cacheFile.isFile) {
                metadata.etag?.let { headers["If-None-Match"] = it }
                metadata.lastModified?.let { headers["If-Modified-Since"] = it }
            }

            try {
                val result = fetcher.fetch(FetchRequest(url = source.url, headers = headers))
                when (result.statusCode) {
                    200 -> {
                        if (result.body.isEmpty()) throw FilterListUpdateException.EmptyBody()
                        CompiledBlocklist.writeAtomically(cacheFile, result.body)
                        metadata = metadata.copy(
                            etag = result.etag,
                            lastModified = result.lastModified,
                            lastFetchedMillis = now(),
                            lastError = null,
                        )
                        summary.updatedSourceIDs.add(source.id)
                    }
                    304 -> {
                        metadata = metadata.copy(lastFetchedMillis = now(), lastError = null)
                        summary.unchangedSourceIDs.add(source.id)
                    }
                    else -> throw FilterListUpdateException.HttpStatus(result.statusCode)
                }
            } catch (error: Exception) {
                val message = shortDescription(error)
                metadata = metadata.copy(lastError = message)
                summary.failedSourceIDs[source.id] = message
            }
            state.metadata[source.id] = metadata
        }

        return summary
    }

    private fun shortDescription(error: Throwable): String = when (error) {
        is FilterListUpdateException -> error.userMessage
        is UnknownHostException -> "Can't reach the server"
        is ConnectException -> "Can't reach the server"
        is SocketTimeoutException -> "Timed out"
        is SSLException -> "Secure connection failed"
        is IOException -> error.message?.takeIf { it.isNotBlank() } ?: "Download failed"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Update failed"
    }
}
