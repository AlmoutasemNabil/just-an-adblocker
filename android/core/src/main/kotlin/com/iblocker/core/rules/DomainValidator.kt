package com.iblocker.core.rules

object DomainValidator {

    /**
     * Normalizes a hostname for hashing and matching: lowercase, trailing
     * dot stripped. Returns null for anything that is not a plausible
     * blockable domain: single labels, empty labels, non-ASCII (v1 skips
     * IDN — the major lists ship punycoded), illegal characters, and
     * over-long names/labels.
     */
    fun normalize(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.endsWith(".")) s = s.dropLast(1)
        if (s.length < 3 || s.length > 253 || !s.contains(".")) return null

        var labelLength = 0
        var previousWasDot = true // catches a leading dot
        for (char in s) {
            when {
                char == '.' -> {
                    if (previousWasDot) return null
                    labelLength = 0
                    previousWasDot = true
                }
                char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_' -> {
                    labelLength += 1
                    if (labelLength > 63) return null
                    previousWasDot = false
                }
                else -> return null
            }
        }
        if (previousWasDot) return null
        return s
    }
}
