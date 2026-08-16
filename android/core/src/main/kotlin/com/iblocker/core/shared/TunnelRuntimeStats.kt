package com.iblocker.core.shared

/** Live counters published by the running VPN service to whoever is watching. */
data class TunnelRuntimeStats(
    val startedAtMillis: Long? = null,
    val totalQueries: Long = 0,
    val blockedQueries: Long = 0,
    val blocklistEntryCount: Long = 0,
    /** Non-null when blocking is currently paused; the instant it resumes. */
    val pausedUntilMillis: Long? = null,
)
