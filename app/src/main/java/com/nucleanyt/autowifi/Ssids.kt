package com.nucleanyt.autowifi

object Ssids {

    private val listNetworksRow = Regex("""^\s*\d+\s+(.*?)\s+(\S+)\s*$""")

    /**
     * Parses the output of `cmd wifi list-networks` (Android 11+), which looks like:
     *
     *     Network Id      SSID                         Security type
     *     0            Home WiFi                        wpa2-psk
     *     3            Cafe                             open
     */
    fun parseListNetworks(output: String): List<String> =
        output.lineSequence()
            .mapNotNull { line -> listNetworksRow.find(line)?.groupValues?.get(1) }
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** Strips the quotes Android puts around SSIDs and drops placeholder values. */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        var s = raw.trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        if (s == "<unknown ssid>" || s == "0x") return ""
        return s
    }
}
