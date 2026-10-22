package com.smartisland.app

/**
 * Pure packet helpers for the local DNS filter: parse an IPv4/IPv6 UDP datagram read from the VPN tunnel, read the DNS question,
 * and build either a NXDOMAIN answer or a reply that wraps an upstream response. No android.* imports (desktop-testable).
 * Deliberately minimal: anything that is not a complete, unfragmented UDP/DNS query is dropped, never guessed at.
 */
class DnsQuery(val v6: Boolean, val src: ByteArray, val dst: ByteArray, val srcPort: Int, val dstPort: Int, val dns: ByteArray)

object DnsPacket {
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }

    fun parse(p: ByteArray, len: Int = p.size): DnsQuery? {
        if (len < 1) return null
        when ((p[0].toInt() ushr 4) and 0xF) {
            4 -> {
                if (len < 28) return null
                val ihl = (p[0].toInt() and 0xF) * 4
                if (ihl < 20 || len < ihl + 8 || (p[9].toInt() and 0xFF) != 17) return null
                val fragOff = ((p[6].toInt() and 0x1F) shl 8) or (p[7].toInt() and 0xFF)
                if (fragOff != 0 || (p[6].toInt() and 0x20) != 0) return null          // fragments are not reassembled
                val total = u16(p, 2)
                if (total > len || total < ihl + 8) return null
                val ulen = u16(p, ihl + 4)
                if (ulen < 8 || ihl + ulen > total) return null
                return DnsQuery(false, p.copyOfRange(12, 16), p.copyOfRange(16, 20), u16(p, ihl), u16(p, ihl + 2), p.copyOfRange(ihl + 8, ihl + ulen))
            }
            6 -> {
                if (len < 48 || (p[6].toInt() and 0xFF) != 17) return null            // extension headers are not followed
                val plen = u16(p, 4)
                if (plen < 8 || 40 + plen > len) return null
                val ulen = u16(p, 44)
                if (ulen < 8 || ulen > plen) return null
                return DnsQuery(true, p.copyOfRange(8, 24), p.copyOfRange(24, 40), u16(p, 40), u16(p, 42), p.copyOfRange(48, 40 + ulen))
            }
        }
        return null
    }

    /** True for a standard query (QR=0, opcode 0) with exactly one well-formed, uncompressed question. */
    fun isStandardQuery(d: ByteArray): Boolean = d.size >= 17 && (d[2].toInt() and 0xF8) == 0 && u16(d, 4) == 1 && questionEnd(d) > 0

    /** Offset just past the question section, or -1 when malformed. */
    private fun questionEnd(d: ByteArray): Int {
        var o = 12
        while (true) {
            if (o >= d.size) return -1
            val l = d[o].toInt() and 0xFF
            if (l == 0) { o++; break }
            if (l and 0xC0 != 0) return -1                                         // compression pointers never occur in a question
            o += l + 1
        }
        return if (o + 4 <= d.size) o + 4 else -1
    }

    fun questionName(d: ByteArray): String? {
        if (!isStandardQuery(d)) return null
        val sb = StringBuilder(); var o = 12
        while (true) {
            val l = d[o].toInt() and 0xFF
            if (l == 0) break
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..l) sb.append((d[o + i].toInt() and 0xFF).toChar())
            o += l + 1
        }
        return sb.toString().lowercase()
    }

    /** NXDOMAIN for the question (all record types, so clients do not retry another type). */
    fun nxdomain(q: ByteArray): ByteArray {
        val end = questionEnd(q).also { require(it > 0) }
        val o = ByteArray(end)
        System.arraycopy(q, 0, o, 0, end)
        val rd = q[2].toInt() and 0x01
        o[2] = (0x80 or rd).toByte()                                               // QR=1, RD copied
        o[3] = (0x80 or 0x03).toByte()                                             // RA=1, RCODE=3
        put16(o, 4, 1); put16(o, 6, 0); put16(o, 8, 0); put16(o, 10, 0)
        return o
    }

    /** Header + question with TC set: used when an upstream answer would not fit the tunnel MTU. */
    fun truncated(resp: ByteArray): ByteArray {
        val end = questionEnd(resp)
        val o = resp.copyOf(if (end > 0) end else minOf(resp.size, 12))
        o[2] = (o[2].toInt() or 0x82).toByte()
        put16(o, 6, 0); put16(o, 8, 0); put16(o, 10, 0)
        return o
    }

    private fun checksum(b: ByteArray, from: Int, len: Int, init: Long = 0L): Int {
        var sum = init; var i = from; val end = from + len
        while (i + 1 < end) { sum += u16(b, i).toLong(); i += 2 }
        if (i < end) sum += ((b[i].toInt() and 0xFF) shl 8).toLong()
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    /** Wraps [dns] in an IP+UDP packet going back to the client of [q]. */
    fun reply(q: DnsQuery, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        if (!q.v6) {
            val total = 20 + udpLen
            val o = ByteArray(total)
            o[0] = 0x45; put16(o, 2, total); o[6] = 0x40; o[8] = 64; o[9] = 17
            System.arraycopy(q.dst, 0, o, 12, 4); System.arraycopy(q.src, 0, o, 16, 4)
            put16(o, 10, checksum(o, 0, 20))
            put16(o, 20, q.dstPort); put16(o, 22, q.srcPort); put16(o, 24, udpLen)   // UDP checksum 0 = none (legal over IPv4)
            System.arraycopy(dns, 0, o, 28, dns.size)
            return o
        }
        val o = ByteArray(40 + udpLen)
        o[0] = 0x60; put16(o, 4, udpLen); o[6] = 17; o[7] = 64
        System.arraycopy(q.dst, 0, o, 8, 16); System.arraycopy(q.src, 0, o, 24, 16)
        put16(o, 40, q.dstPort); put16(o, 42, q.srcPort); put16(o, 44, udpLen)
        System.arraycopy(dns, 0, o, 48, dns.size)
        var pseudo = 0L
        for (i in 8 until 40 step 2) pseudo += u16(o, i).toLong()
        pseudo += udpLen.toLong() + 17L
        var c = checksum(o, 40, udpLen, pseudo)
        if (c == 0) c = 0xFFFF                                                      // UDP over IPv6: a zero checksum is not allowed
        put16(o, 46, c)
        return o
    }
}
