package com.iblocker.core.log

import com.iblocker.core.json.Json
import com.iblocker.core.json.asLong
import com.iblocker.core.json.asObject
import com.iblocker.core.rules.CompiledBlocklist
import java.io.File
import java.time.Instant
import java.time.ZoneId

data class DayCounters(var total: Long = 0, var blocked: Long = 0)

/**
 * Cumulative counters, persisted as JSON. The VPN service keeps the
 * authoritative copy in memory and flushes on the log-ring cadence; the UI
 * reads the file when the service is not running.
 */
class BlockerStats(
    var totalQueries: Long = 0,
    var totalBlocked: Long = 0,
    val days: MutableMap<String, DayCounters> = LinkedHashMap(),
) {
    fun record(blocked: Boolean, day: String) {
        totalQueries += 1
        val counters = days.getOrPut(day) { DayCounters() }
        counters.total += 1
        if (blocked) {
            totalBlocked += 1
            counters.blocked += 1
        }
        if (days.size > 60) trim(keepDays = 45)
    }

    fun trim(keepDays: Int) {
        val keep = days.keys.sorted().takeLast(keepDays).toSet()
        days.keys.retainAll(keep)
    }

    fun counters(day: String): DayCounters = days[day] ?: DayCounters()

    fun toJson(): Map<String, Any?> = mapOf(
        "totalQueries" to totalQueries,
        "totalBlocked" to totalBlocked,
        "days" to days.mapValues { (_, counters) ->
            mapOf("total" to counters.total, "blocked" to counters.blocked)
        },
    )

    companion object {
        /** Local-calendar day key, e.g. "2026-08-09". */
        fun dayKey(epochMillis: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
            val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            return String.format("%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
        }

        fun fromJson(value: Any?): BlockerStats {
            val root = value.asObject() ?: return BlockerStats()
            val stats = BlockerStats(
                totalQueries = root["totalQueries"].asLong() ?: 0,
                totalBlocked = root["totalBlocked"].asLong() ?: 0,
            )
            root["days"].asObject()?.forEach { (day, counters) ->
                val entry = counters.asObject() ?: return@forEach
                stats.days[day] = DayCounters(
                    total = entry["total"].asLong() ?: 0,
                    blocked = entry["blocked"].asLong() ?: 0,
                )
            }
            return stats
        }
    }
}

object StatsPersistence {
    fun load(file: File): BlockerStats = try {
        if (file.isFile) BlockerStats.fromJson(Json.parseOrNull(file.readText())) else BlockerStats()
    } catch (_: Exception) {
        BlockerStats()
    }

    fun save(stats: BlockerStats, file: File) {
        runCatching {
            CompiledBlocklist.writeAtomically(file, Json.write(stats.toJson()).toByteArray(Charsets.UTF_8))
        }
    }
}
