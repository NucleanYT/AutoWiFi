package com.nucleanyt.autowifi

object AppOps {

    private val modeLine = Regex("""^\S.*?:\s*(allow|deny|ignore|default|foreground)\b""", RegexOption.MULTILINE)

    /**
     * Reads the mode from `appops get <package> <op>`, which prints e.g.
     * "MIUIOP(10008): allow; time=+1d" or "AUTO_START: deny". `null` if it can't tell.
     */
    fun parseAllowed(output: String): Boolean? {
        val mode = modeLine.find(output)?.groupValues?.get(1) ?: return null
        return mode == "allow"
    }
}
