package com.iblocker.android.data

import com.iblocker.core.log.LogVerdict
import com.iblocker.core.log.QueryLogRecord
import com.iblocker.core.log.QueryLogRingReader
import com.iblocker.core.shared.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Tails the query-log ring by monotonic cursor — the reader half of the
 * format the VPN service writes. Shared by the Log tab and the Dashboard
 * chart so one poll feeds both.
 */
class QueryLogStore(paths: AppPaths) {

    private val reader = QueryLogRingReader(paths.queryLogFile)
    private var cursor = 0L

    private val _records = MutableStateFlow<List<QueryLogRecord>>(emptyList())
    val records: StateFlow<List<QueryLogRecord>> = _records.asStateFlow()

    suspend fun poll() {
        val tail = withContext(Dispatchers.IO) { reader.read(since = cursor) }
        if (tail.cursor == cursor) return
        cursor = tail.cursor
        if (tail.records.isEmpty()) return

        val merged = _records.value + tail.records
        _records.value = if (merged.size > MAX_RECORDS) merged.takeLast(MAX_RECORDS) else merged
    }

    /** Blocked count per hour of the local day, for the dashboard chart. */
    fun blockedPerHourToday(now: Long = System.currentTimeMillis()): List<Int> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val buckets = IntArray(24)
        for (record in _records.value) {
            if (record.verdict != LogVerdict.BLOCKED) continue
            val at = Instant.ofEpochMilli(record.timestampMillis).atZone(zone)
            if (at.toLocalDate() != today) continue
            buckets[at.hour] += 1
        }
        return buckets.toList()
    }

    fun topBlockedDomains(limit: Int = 5): List<Pair<String, Int>> =
        _records.value
            .filter { it.verdict == LogVerdict.BLOCKED }
            .groupingBy { it.domain }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    private companion object {
        const val MAX_RECORDS = 4000
    }
}
