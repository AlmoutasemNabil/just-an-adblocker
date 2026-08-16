package com.iblocker.core.shared

import com.iblocker.core.json.Json
import com.iblocker.core.json.asBoolean
import com.iblocker.core.json.asLong
import com.iblocker.core.json.asObject
import com.iblocker.core.json.asString
import com.iblocker.core.json.asStringList
import com.iblocker.core.rules.CompiledBlocklist
import java.io.File

/**
 * How the filter deals with apps that can route DNS around the system
 * resolver (encrypted DNS straight to a hardcoded endpoint). This is the
 * Android counterpart of the iOS build's Apple-relay strategy: same shape,
 * different bypass path.
 */
enum class BypassStrategy(val raw: String) {
    /** DNS-block the known DoH/DoT endpoints so those apps fall back to system DNS. */
    BLOCK_BYPASS_DOMAINS("blockBypassDomains"),

    /** Touch nothing: encrypted-DNS apps keep their own resolver, and their ad lookups with it. */
    ALLOW_ENCRYPTED_DNS("allowEncryptedDns"),
    ;

    companion object {
        fun from(raw: String?): BypassStrategy =
            entries.firstOrNull { it.raw == raw } ?: BLOCK_BYPASS_DOMAINS
    }
}

/** Upstream resolver configuration, read by the VPN service at start and over the control channel. */
data class UpstreamConfig(
    val kind: Kind,
    /**
     * DoH endpoint. Presets use IP-literal URLs (https://1.1.1.1/dns-query)
     * because the service's own hostname lookups would recurse through the
     * tun interface it is serving.
     */
    val dohURL: String? = null,
    /** Plain-DNS server IP for UDP upstreams. */
    val udpAddress: String? = null,
) {
    enum class Kind(val raw: String) {
        DOH("doh"),
        UDP("udp"),
        ;

        companion object {
            fun from(raw: String?): Kind = entries.firstOrNull { it.raw == raw } ?: DOH
        }
    }

    val displayName: String
        get() = when (kind) {
            Kind.DOH -> dohURL ?: "DoH"
            Kind.UDP -> "${udpAddress ?: "?"}:53 (UDP)"
        }

    fun toJson(): Map<String, Any?> = mapOf(
        "kind" to kind.raw,
        "dohURL" to dohURL,
        "udpAddress" to udpAddress,
    )

    companion object {
        /** Cloudflare via IP-literal DoH: fast, neutral, no bootstrap lookup. */
        val DEFAULT = UpstreamConfig(Kind.DOH, dohURL = "https://1.1.1.1/dns-query")

        fun fromJson(value: Any?): UpstreamConfig {
            val map = value.asObject() ?: return DEFAULT
            return UpstreamConfig(
                kind = Kind.from(map["kind"].asString()),
                dohURL = map["dohURL"].asString(),
                udpAddress = map["udpAddress"].asString(),
            )
        }
    }
}

/**
 * Everything the app and the VPN service both need to agree on, as one small
 * JSON document. (iOS uses the App Group's shared UserDefaults for this; a
 * plain file behaves identically and survives the service running in its own
 * process.)
 */
data class IBlockerSettings(
    val upstream: UpstreamConfig = UpstreamConfig.DEFAULT,
    val onboardingComplete: Boolean = false,
    val queryLogEnabled: Boolean = true,
    val lastListUpdateMillis: Long? = null,
    val bypassStrategy: BypassStrategy = BypassStrategy.BLOCK_BYPASS_DOMAINS,
    /**
     * When set to a future instant, the service forwards everything without
     * blocking (a temporary "let this through" for a broken checkout, captcha
     * or link). Any entry point — UI, tile, widget, shortcut — can write it.
     */
    val pausedUntilMillis: Long? = null,
    /** Mirror of the service's running state, so the tile and widget can draw without binding. */
    val protectionActive: Boolean = false,
    /** Start protection again after a reboot (Android's answer to iOS on-demand rules). */
    val autoStartOnBoot: Boolean = true,
    /** Package names excluded from the tunnel entirely — their DNS is never filtered. */
    val excludedPackages: List<String> = emptyList(),
) {
    /** The pause deadline, but only while it is still in the future. */
    fun activePauseUntil(now: Long = System.currentTimeMillis()): Long? =
        pausedUntilMillis?.takeIf { it > now }

    fun toJson(): Map<String, Any?> = mapOf(
        "upstream" to upstream.toJson(),
        "onboardingComplete" to onboardingComplete,
        "queryLogEnabled" to queryLogEnabled,
        "lastListUpdateMillis" to lastListUpdateMillis,
        "bypassStrategy" to bypassStrategy.raw,
        "pausedUntilMillis" to pausedUntilMillis,
        "protectionActive" to protectionActive,
        "autoStartOnBoot" to autoStartOnBoot,
        "excludedPackages" to excludedPackages,
    )

    companion object {
        fun fromJson(value: Any?): IBlockerSettings {
            val map = value.asObject() ?: return IBlockerSettings()
            val defaults = IBlockerSettings()
            return IBlockerSettings(
                upstream = UpstreamConfig.fromJson(map["upstream"]),
                onboardingComplete = map["onboardingComplete"].asBoolean() ?: defaults.onboardingComplete,
                queryLogEnabled = map["queryLogEnabled"].asBoolean() ?: defaults.queryLogEnabled,
                lastListUpdateMillis = map["lastListUpdateMillis"].asLong(),
                bypassStrategy = BypassStrategy.from(map["bypassStrategy"].asString()),
                pausedUntilMillis = map["pausedUntilMillis"].asLong(),
                protectionActive = map["protectionActive"].asBoolean() ?: defaults.protectionActive,
                autoStartOnBoot = map["autoStartOnBoot"].asBoolean() ?: defaults.autoStartOnBoot,
                excludedPackages = map["excludedPackages"].asStringList(),
            )
        }
    }
}

/** File-backed, read-through accessor for [IBlockerSettings]. Safe to call from any thread. */
class SettingsStore(private val file: File) {

    @Volatile
    private var cached: IBlockerSettings? = null

    fun load(): IBlockerSettings {
        cached?.let { return it }
        val loaded = try {
            if (file.isFile) IBlockerSettings.fromJson(Json.parseOrNull(file.readText())) else IBlockerSettings()
        } catch (_: Exception) {
            IBlockerSettings()
        }
        cached = loaded
        return loaded
    }

    /** Re-reads from disk, for readers that must see another process's write. */
    fun reload(): IBlockerSettings {
        cached = null
        return load()
    }

    @Synchronized
    fun save(settings: IBlockerSettings) {
        cached = settings
        runCatching {
            CompiledBlocklist.writeAtomically(
                file,
                Json.write(settings.toJson(), pretty = true).toByteArray(Charsets.UTF_8),
            )
        }
    }

    @Synchronized
    fun update(transform: (IBlockerSettings) -> IBlockerSettings): IBlockerSettings {
        val updated = transform(reload())
        save(updated)
        return updated
    }
}
