package dev.wyz.clientcore.config

/** Names already passed through ConfigService.sanitizeName, with filesystem collision checks. */
object ProfileNames {
    const val MAX_LENGTH = 24

    fun available(baseName: String, exists: (String) -> Boolean): String {
        var name = baseName
        var number = 2
        while (exists(name)) {
            val suffix = " $number"
            // Reserve the complete suffix before truncating, including when its digit count grows.
            name = baseName.take(MAX_LENGTH - suffix.length).trimEnd() + suffix
            number += 1
        }
        return name
    }
}
