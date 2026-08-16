package com.iblocker.core.lists

import com.iblocker.core.json.Json
import com.iblocker.core.json.asArray
import com.iblocker.core.json.asBoolean
import com.iblocker.core.json.asLong
import com.iblocker.core.json.asObject
import com.iblocker.core.json.asString
import com.iblocker.core.json.asStringList
import com.iblocker.core.json.asUInt
import com.iblocker.core.rules.CompiledBlocklist
import com.iblocker.core.rules.SeedRules
import java.io.File

/**
 * A DNS blocklist source. Built-ins ship with the app; users can add any URL
 * serving hosts/domain/AdGuard-format text.
 */
data class FilterListSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val isBuiltIn: Boolean,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "url" to url,
        "enabled" to enabled,
        "isBuiltIn" to isBuiltIn,
    )

    companion object {
        fun fromJson(value: Any?): FilterListSource? {
            val map = value.asObject() ?: return null
            val id = map["id"].asString() ?: return null
            val url = map["url"].asString() ?: return null
            return FilterListSource(
                id = id,
                name = map["name"].asString() ?: id,
                url = url,
                enabled = map["enabled"].asBoolean() ?: true,
                isBuiltIn = map["isBuiltIn"].asBoolean() ?: false,
            )
        }

        /**
         * The bundled rulesets and OISD start enabled: the core set is the
         * guaranteed in-app-ad blocking floor (compiled into the app, no
         * download needed), OISD adds broad ad/tracker coverage with a strict
         * no-breakage policy. The others are one tap away.
         */
        val builtIn: List<FilterListSource> = listOf(
            FilterListSource(
                id = SeedRules.SOURCE_ID,
                name = "Core mobile ad networks (built-in)",
                url = "https://bundled.invalid/core",
                enabled = true,
                isBuiltIn = true,
            ),
            FilterListSource(
                id = SeedRules.BYPASS_SOURCE_ID,
                name = "Block encrypted-DNS bypass (in-app ad fix)",
                url = "https://bundled.invalid/dns-bypass",
                enabled = true,
                isBuiltIn = true,
            ),
            FilterListSource(
                id = "oisd-big",
                name = "OISD Big",
                url = "https://big.oisd.nl",
                enabled = true,
                isBuiltIn = true,
            ),
            FilterListSource(
                id = "hagezi-pro",
                name = "HaGeZi Multi Pro",
                url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt",
                enabled = false,
                isBuiltIn = true,
            ),
            FilterListSource(
                id = "stevenblack",
                name = "StevenBlack Hosts",
                url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                enabled = false,
                isBuiltIn = true,
            ),
            FilterListSource(
                id = "adguard-dns",
                name = "AdGuard DNS Filter",
                url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                enabled = false,
                isBuiltIn = true,
            ),
        )
    }
}

data class FilterListMetadata(
    val etag: String? = null,
    val lastModified: String? = null,
    val lastFetchedMillis: Long? = null,
    val entryCount: Int = 0,
    val lastError: String? = null,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "etag" to etag,
        "lastModified" to lastModified,
        "lastFetchedMillis" to lastFetchedMillis,
        "entryCount" to entryCount.toLong(),
        "lastError" to lastError,
    )

    companion object {
        fun fromJson(value: Any?): FilterListMetadata {
            val map = value.asObject() ?: return FilterListMetadata()
            return FilterListMetadata(
                etag = map["etag"].asString(),
                lastModified = map["lastModified"].asString(),
                lastFetchedMillis = map["lastFetchedMillis"].asLong(),
                entryCount = (map["entryCount"].asLong() ?: 0L).toInt(),
                lastError = map["lastError"].asString(),
            )
        }
    }
}

/** Everything the Lists feature persists, as one JSON document (sources.json). */
data class FilterListState(
    var sources: MutableList<FilterListSource> = FilterListSource.builtIn.toMutableList(),
    var metadata: MutableMap<String, FilterListMetadata> = LinkedHashMap(),
    var userAllowlist: MutableList<String> = ArrayList(),
    var userDenylist: MutableList<String> = ArrayList(),
    var generation: UInt = 0u,
    /**
     * [SeedRules.VERSION] at the time of the last compile. When the app
     * updates with changed built-in rules, the mismatch triggers an immediate
     * recompile at launch. Null when saved by a build that predates the stamp.
     */
    var compiledSeedVersion: UInt? = null,
) {
    fun copyState(): FilterListState = FilterListState(
        sources = sources.toMutableList(),
        metadata = LinkedHashMap(metadata),
        userAllowlist = userAllowlist.toMutableList(),
        userDenylist = userDenylist.toMutableList(),
        generation = generation,
        compiledSeedVersion = compiledSeedVersion,
    )

    fun toJson(): Map<String, Any?> = mapOf(
        "sources" to sources.map { it.toJson() },
        "metadata" to metadata.mapValues { it.value.toJson() },
        "userAllowlist" to userAllowlist,
        "userDenylist" to userDenylist,
        "generation" to generation.toLong(),
        "compiledSeedVersion" to compiledSeedVersion?.toLong(),
    )

    fun save(file: File) {
        CompiledBlocklist.writeAtomically(file, Json.write(toJson(), pretty = true).toByteArray(Charsets.UTF_8))
    }

    companion object {
        fun fromJson(value: Any?): FilterListState {
            val map = value.asObject() ?: return FilterListState()
            val sources = map["sources"].asArray()
                ?.mapNotNull { FilterListSource.fromJson(it) }
                ?.toMutableList()
                ?: FilterListSource.builtIn.toMutableList()
            val metadata = LinkedHashMap<String, FilterListMetadata>()
            map["metadata"].asObject()?.forEach { (id, entry) ->
                metadata[id] = FilterListMetadata.fromJson(entry)
            }
            return FilterListState(
                sources = sources,
                metadata = metadata,
                userAllowlist = map["userAllowlist"].asStringList().toMutableList(),
                userDenylist = map["userDenylist"].asStringList().toMutableList(),
                generation = map["generation"].asUInt() ?: 0u,
                compiledSeedVersion = map["compiledSeedVersion"].asUInt(),
            )
        }

        fun load(file: File): FilterListState {
            val state = try {
                if (file.isFile) fromJson(Json.parseOrNull(file.readText())) else FilterListState()
            } catch (_: Exception) {
                FilterListState()
            }
            // Merge in any built-ins added by app updates.
            val known = state.sources.map { it.id }.toSet()
            for (builtIn in FilterListSource.builtIn) {
                if (builtIn.id !in known) state.sources.add(builtIn)
            }
            return state
        }
    }
}
