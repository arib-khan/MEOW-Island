package com.smartisland.app

import java.io.BufferedReader

/**
 * Pure domain-matching logic for the adult website filter. Loads hosts files, plain domain lists and Adblock-style "||domain^"
 * lines. A domain blocks itself and all of its subdomains; the most specific rule wins, so an allow-list entry for a subdomain
 * beats a block on its parent.
 */
class DomainBlocklist {
    private class Snap(val blocked: HashSet<String>, val allow: HashSet<String>, val custom: HashSet<String>)
    @Volatile private var snap = Snap(HashSet(), HashSet(), HashSet())
    @Volatile var listSize = 0; private set

    companion object {
        /** Top-level domains that exist only for adult content. */
        val ADULT_TLDS = setOf("xxx", "porn", "adult", "sex")
        /** Firefox "canary" domain: a NXDOMAIN answer makes Firefox keep using the system resolver instead of its own DoH. */
        const val DOH_CANARY = "use-application-dns.net"
        private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?$")

        fun normalize(raw: String?): String? {
            var s = (raw ?: return null).trim().lowercase().trimEnd('.')
            if (s.startsWith("*.")) s = s.substring(2)
            if (s.isEmpty() || s.length > 253) return null
            val labels = s.split('.')
            if (labels.any { it.isEmpty() || it.length > 63 || !LABEL.matches(it) }) return null
            return s
        }

        /** Extracts one domain from a hosts / domain-list / Adblock line, or null for comments, IPs and junk. */
        fun parseLine(line: String): String? {
            var l = line.trim()
            if (l.isEmpty() || l[0] == '#' || l[0] == '!' || l[0] == '[') return null
            val hash = l.indexOf('#'); if (hash > 0) l = l.substring(0, hash).trim()
            if (l.startsWith("||")) { l = l.removePrefix("||"); val c = l.indexOfAny(charArrayOf('^', '$', '/')); if (c >= 0) l = l.substring(0, c) }
            else { val parts = l.split(Regex("\\s+")); l = if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::")) parts[1] else if (parts.size == 1) parts[0] else return null }
            val d = normalize(l) ?: return null
            if (!d.contains('.') || d == "localhost" || d.all { it.isDigit() || it == '.' }) return null
            return d
        }
    }

    /** Replaces the downloaded/bundled list. Returns the number of domains loaded. */
    fun load(reader: BufferedReader): Int {
        val set = HashSet<String>(100_000)
        reader.forEachLine { parseLine(it)?.let { d -> set.add(d) } }
        val cur = snap
        snap = Snap(set, cur.allow, cur.custom)
        listSize = set.size
        return set.size
    }

    fun setUser(allow: Collection<String>, block: Collection<String>) {
        val cur = snap
        snap = Snap(cur.blocked, allow.mapNotNull { normalize(it) }.toHashSet(), block.mapNotNull { normalize(it) }.toHashSet())
    }

    fun isBlocked(host: String?): Boolean {
        val h = normalize(host) ?: return false
        if (h == DOH_CANARY) return true
        val s = snap
        var cur = h
        while (true) {
            if (cur in s.allow) return false
            if (cur in s.custom || cur in s.blocked) return true
            val dot = cur.indexOf('.'); if (dot < 0) break
            cur = cur.substring(dot + 1)
        }
        return h.substringAfterLast('.') in ADULT_TLDS
    }
}
