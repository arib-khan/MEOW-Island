import com.smartisland.app.*
import java.io.BufferedReader
import java.io.StringReader
import java.util.TimeZone

var passed = 0
fun check(c: Boolean, m: String) { if (!c) throw AssertionError("FAIL: $m"); passed++ }
fun n(id: String, text: String? = null, desc: String? = null, vis: Boolean = true, top: Int = 0, bottom: Int = 2000) = NodeInfo(id, text, desc, vis, top, bottom)
fun finder(vararg ns: NodeInfo): (String) -> List<NodeInfo> = { id -> ns.filter { it.id == id } }

fun main() {
    // ---------- detector ----------
    val ig = ShortsDetector.detect(ShortsApp.IG_PKG, 1000, finder(n("clips_viewer_view_pager"), n("clips_author_username", "alice", top = 900, bottom = 1100), n("clips_author_username", "bob", top = 2000, bottom = 3000)))
    check(ig.onFeed && ig.app == ShortsApp.IG && ig.sig != null, "IG reels detected")
    val ig2 = ShortsDetector.detect(ShortsApp.IG_PKG, 1000, finder(n("clips_viewer_view_pager"), n("clips_author_username", "bob", top = 900, bottom = 1100), n("clips_author_username", "alice", top = -1100, bottom = -100)))
    check(ig.sig != ig2.sig, "signature follows the item at screen centre, not the preloaded neighbour")
    check(!ShortsDetector.detect(ShortsApp.IG_PKG, 1000, finder(n("feed_tab"), n("row_feed_photo_profile_name", "alice"))).onFeed, "ordinary IG screen is not a feed")
    check(!ShortsDetector.detect(ShortsApp.IG_PKG, 1000, finder(n("reel_viewer_texture_view"))).onFeed, "IG Stories (reel_viewer_*) never match")
    check(!ShortsDetector.detect(ShortsApp.IG_PKG, 1000, finder(n("clips_viewer_view_pager", vis = false))).onFeed, "invisible pager does not count")
    check(ShortsDetector.detect(ShortsApp.YT_PKG, 1000, finder(n("reel_recycler"))).onFeed, "YT Shorts detected")
    check(!ShortsDetector.detect(ShortsApp.YT_PKG, 1000, finder(n("watch_player"), n("comments_entry_point_header"))).onFeed, "regular YT video / comments are not Shorts")
    check(!ShortsDetector.detect("com.android.chrome", 1000, finder(n("reel_recycler"))).onFeed, "other packages never match")
    check(ShortsDetector.detect(ShortsApp.YT_PKG, 1000, finder(n("reel_recycler"))).sig == null, "no usable text -> null signature (fallback mode)")

    // ---------- counter ----------
    var c = ShortsCounter(settleMs = 300)
    check(!c.observe("A", 0), "first sight waits to settle")
    check(!c.observe("A", 200), "still settling")
    check(c.observe("A", 350), "A counted once settled")
    check(!c.observe("A", 5000), "same item never counted twice")
    check(!c.observe("B", 6000) && c.observe("B", 6400), "B counted after settling")
    check(!c.observe("A", 7000) && !c.observe("A", 7400), "swiping back to A within memory is not recounted")
    var cnt = 0; var t = 10_000L
    for (s in listOf("C", "D", "E", "F")) { c.observe(s, t); t += 400; if (c.observe(s, t)) cnt++; t += 10 }
    check(cnt == 4, "four distinct items = four counts")
    c = ShortsCounter(settleMs = 300)
    c.observe("X", 0); c.observe("Y", 100); c.observe("X", 200); c.observe("Y", 300)      // flicker between two values
    check(!c.observe("Y", 350), "a flickering tree never settles, so it is never counted")
    c = ShortsCounter(settleMs = 300)
    c.observe("A", 0); check(c.observe("A", 400), "counted"); c.exit()
    c.observe("A", 20_000); check(!c.observe("A", 20_400), "returning to the same item within memory window: not recounted")
    c.exit(); c.observe("A", 500_000); check(c.observe("A", 500_400), "same item long afterwards counts again")
    check(!ShortsCounter().observe(null, 0), "null signature never counts")
    val s = ShortsCounter(scrollDebounceMs = 900)
    check(s.observeScroll(1000) && !s.observeScroll(1500) && s.observeScroll(2000), "scroll fallback is debounced")

    // ---------- milestones (defaults from the spec) ----------
    val d = Milestones.DEFAULT
    check(Milestones.pick(0, d) == null, "0 -> no tier")
    for ((k, e) in listOf(1 to "😺", 9 to "😺", 10 to "😼", 24 to "😼", 25 to "😿", 49 to "😿", 50 to "🙀", 99 to "🙀", 100 to "😾", 5000 to "😾")) check(Milestones.pick(k, d)?.emoji == e, "tier for $k")
    check(Milestones.crossed(9, 10, d)?.min == 10 && Milestones.crossed(10, 11, d) == null && Milestones.crossed(0, 60, d)?.min == 50, "crossing detection")
    val custom = listOf(Tier(5, "a", "x"), Tier(7, "b", "y"))
    check(Milestones.pick(6, custom)?.emoji == "a" && Milestones.pick(4, custom) == null, "thresholds are configurable")

    // ---------- policy ----------
    check(ShortsPolicy.shouldBlock(ShortsApp.IG, true, false, false, 0, 1000), "IG blocked when enabled")
    check(!ShortsPolicy.shouldBlock(ShortsApp.YT, true, false, false, 0, 1000), "YT not blocked when only IG enabled")
    check(!ShortsPolicy.shouldBlock(ShortsApp.IG, true, true, false, 5000, 1000), "non-strict snooze lets it through")
    check(ShortsPolicy.shouldBlock(ShortsApp.IG, true, true, true, 5000, 1000), "strict ignores any snooze")
    check(!ShortsPolicy.shouldBlock(null, true, true, true, 0, 0), "non-target app is never blocked")
    val utc = TimeZone.getTimeZone("UTC")
    check(ShortsPolicy.dayKey(0L, utc) == "1970-01-01" && ShortsPolicy.dayKey(86_399_999L, utc) == "1970-01-01" && ShortsPolicy.dayKey(86_400_000L, utc) == "1970-01-02", "day rollover")
    check(ShortsPolicy.goalNudge(9, 10, 10, 5) == 1 && ShortsPolicy.goalNudge(10, 11, 10, 5) == 0 && ShortsPolicy.goalNudge(14, 15, 10, 5) == 2 && ShortsPolicy.goalNudge(1, 2, 0, 5) == 0 && ShortsPolicy.goalNudge(14, 15, 10, 0) == 0, "goal reminders")

    // ---------- blocklist ----------
    val l = DomainBlocklist()
    l.load(BufferedReader(StringReader("# c\n0.0.0.0 bad.example\n127.0.0.1 localhost\n||adblock.test^\nplain.test\n0.0.0.0 0.0.0.0\n1.2.3.4\n! x\nUPPER.TEST # trailing\n")))
    check(l.listSize == 4, "parses hosts, adblock, plain, comments; skips localhost / IPs (got ${l.listSize})")
    check(l.isBlocked("bad.example") && l.isBlocked("www.bad.example") && l.isBlocked("a.b.c.bad.example."), "domain + subdomains")
    check(!l.isBlocked("notbad.example") && !l.isBlocked("example") && !l.isBlocked("good.example"), "no false suffix matches")
    check(l.isBlocked("upper.test") && l.isBlocked("WWW.Plain.TEST"), "case-insensitive")
    check(l.isBlocked("anything.xxx") && l.isBlocked("x.sex") && !l.isBlocked("sex.com.au.example"), "adult-only TLDs")
    check(l.isBlocked("use-application-dns.net"), "Firefox DoH canary answers NXDOMAIN")
    l.setUser(listOf("www.bad.example", "*.fine.xxx"), listOf("custom.block"))
    check(!l.isBlocked("www.bad.example") && !l.isBlocked("cdn.www.bad.example") && l.isBlocked("bad.example") && l.isBlocked("api.bad.example"), "allow-list beats a parent block, most specific wins")
    check(!l.isBlocked("fine.xxx") && l.isBlocked("custom.block") && l.isBlocked("a.custom.block"), "custom allow / block")
    check(!l.isBlocked("") && !l.isBlocked(null) && !l.isBlocked("bad domain"), "garbage input is not blocked and does not crash")

    // ---------- DNS packets ----------
    fun query(name: String, qtype: Int = 1, id: Int = 0x1234): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(id shr 8); b.write(id and 0xFF); b.write(0x01); b.write(0x00); b.write(0); b.write(1); repeat(6) { b.write(0) }
        for (lab in name.split('.')) { b.write(lab.length); b.write(lab.toByteArray()) }
        b.write(0); b.write(qtype shr 8); b.write(qtype and 0xFF); b.write(0); b.write(1)
        return b.toByteArray()
    }
    fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    fun v4(dns: ByteArray): ByteArray {
        val total = 28 + dns.size; val o = ByteArray(total)
        o[0] = 0x45; o[2] = (total shr 8).toByte(); o[3] = total.toByte(); o[8] = 64; o[9] = 17
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(o, 12); byteArrayOf(10, 111, 222.toByte(), 1).copyInto(o, 16)
        o[20] = 0x9C.toByte(); o[21] = 0x40; o[23] = 53; o[24] = ((8 + dns.size) shr 8).toByte(); o[25] = (8 + dns.size).toByte()
        dns.copyInto(o, 28); return o
    }
    fun v6(dns: ByteArray): ByteArray {
        val o = ByteArray(48 + dns.size); o[0] = 0x60; o[4] = ((8 + dns.size) shr 8).toByte(); o[5] = (8 + dns.size).toByte(); o[6] = 17; o[7] = 64
        o[8] = 0xfd.toByte(); o[23] = 2; o[24] = 0xfd.toByte(); o[39] = 1
        o[40] = 0x9C.toByte(); o[41] = 0x40; o[43] = 53; o[44] = ((8 + dns.size) shr 8).toByte(); o[45] = (8 + dns.size).toByte()
        dns.copyInto(o, 48); return o
    }
    val q = query("Www.Bad.Example"); val p4 = DnsPacket.parse(v4(q))!!
    check(!p4.v6 && p4.dstPort == 53 && p4.srcPort == 0x9C40 && DnsPacket.questionName(p4.dns) == "www.bad.example", "IPv4 query parsed")
    val nx = DnsPacket.nxdomain(p4.dns)
    check(u16(nx, 0) == 0x1234 && (nx[2].toInt() and 0x80) != 0 && (nx[3].toInt() and 0x0F) == 3 && u16(nx, 4) == 1 && u16(nx, 6) == 0 && DnsPacket.questionName(nx.also { it[2] = (it[2].toInt() and 0x7F).toByte() }) == "www.bad.example", "NXDOMAIN keeps id + question")
    val r4 = DnsPacket.reply(p4, nx)
    check(r4.size == 28 + nx.size && (r4[0].toInt() and 0xFF) == 0x45 && u16(r4, 22) == 0x9C40 && u16(r4, 20) == 53 && r4[12] == 10.toByte() && r4[15] == 1.toByte(), "IPv4 reply swaps addresses and ports")
    var sum = 0L; for (i in 0 until 20 step 2) sum += u16(r4, i); while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    check(sum == 0xFFFFL, "IPv4 header checksum verifies")
    val p6 = DnsPacket.parse(v6(query("a.b.c", 28)))!!
    check(p6.v6 && DnsPacket.questionName(p6.dns) == "a.b.c", "IPv6 query parsed")
    val r6 = DnsPacket.reply(p6, DnsPacket.nxdomain(p6.dns))
    var s6 = 0L; for (i in 8 until 40 step 2) s6 += u16(r6, i); s6 += (r6.size - 40).toLong() + 17; for (i in 40 until r6.size - 1 step 2) s6 += u16(r6, i); if ((r6.size - 40) % 2 == 1) s6 += (r6[r6.size - 1].toInt() and 0xFF) shl 8
    while (s6 shr 16 != 0L) s6 = (s6 and 0xFFFF) + (s6 shr 16)
    check(s6 == 0xFFFFL && r6[24] == 0xfd.toByte() && r6[23] == 1.toByte(), "IPv6 UDP checksum verifies, addresses swapped")
    check(DnsPacket.parse(ByteArray(10)) == null && DnsPacket.parse(byteArrayOf()) == null, "short packets are dropped")
    val frag = v4(q); frag[6] = 0x20; check(DnsPacket.parse(frag) == null, "IP fragments are dropped")
    val tcp = v4(q); tcp[9] = 6; check(DnsPacket.parse(tcp) == null, "non-UDP is dropped")
    val lie = v4(q); lie[24] = 0x7F; check(DnsPacket.parse(lie) == null, "lying UDP length is rejected")
    val comp = query("x.y"); comp[12] = 0xC0.toByte(); check(DnsPacket.questionName(comp) == null, "compressed question rejected")
    val resp = query("x.y"); resp[2] = 0x81.toByte(); check(DnsPacket.questionName(resp) == null, "responses are not treated as queries")
    check((DnsPacket.truncated(ByteArray(300) { 1 }.also { query("x.y").copyInto(it) })[2].toInt() and 0x02) != 0, "truncation sets TC")
    println("Kotlin logic tests: $passed checks passed")
}
