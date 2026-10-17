package com.nucleanyt.autowifi

/** Matches Quick settings tile text like "Wi-Fi", "Mobile data,Off" or "Wi‑Fi, On, HomeNet". */
object TileLabels {

    private val stateWords = setOf("on", "off", "enabled", "disabled", "turned", "connected", "disconnected")

    fun normalize(value: CharSequence?): String =
        value?.toString().orEmpty()
            .replace(Regex("[‐‑‒–—−]"), "-")
            .replace(' ', ' ')
            .trim()
            .lowercase()

    /** [labels] must already be normalized. */
    fun matches(value: CharSequence?, labels: List<String>): Boolean {
        val text = normalize(value)
        if (text.isEmpty()) return false
        return labels.any { label ->
            if (!text.startsWith(label)) return@any false
            val rest = text.substring(label.length)
            when {
                rest.isEmpty() -> true
                rest[0] in ",.;:\n" -> true
                // "Wi-Fi on" is the tile; "Wi-Fi calling" is a different tile.
                rest[0] == ' ' -> rest.trim().split(' ', ',', '.').first() in stateWords
                else -> false
            }
        }
    }
}
