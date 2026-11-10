import com.smartisland.app.DeviceProtocol as P
import java.io.File

var passed = 0
fun check(c: Boolean, m: String) { if (!c) throw AssertionError("FAIL: $m"); passed++ }
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    val out = StringBuilder()
    fun vec(name: String, frame: ByteArray, summary: String) { out.append("$name|${hex(frame)}|$summary\n") }

    // ---- framing ----
    check(hex(P.hello()) == "a5010101", "hello frame")
    check(hex(P.screen(2)) == "a5020102" && hex(P.screen(9)) == "a5020105" && hex(P.screen(-3)) == "a5020100", "screen is clamped")
    vec("HELLO", P.hello(), "proto=1")
    for (s in 0..5) vec("SCREEN", P.screen(s), "screen=$s")
    vec("TIME_IST", P.time(1_800_000_000L, 330), "epoch=1800000000 tz=330 tzs=<+0530>-5:30")
    vec("TIME_EST", P.time(1_800_000_000L, -300), "epoch=1800000000 tz=-300 tzs=<-0500>+5")
    vec("TIME_UTC", P.time(1_800_000_000L, 0), "epoch=1800000000 tz=0 tzs=<+0000>-0")
    vec("WIFI_CLEAR", P.wifiClear(), "wificlear")
    vec("UNPAIR", P.unpair(false), "flags=0"); vec("UNPAIR_ALL", P.unpair(true), "flags=1")

    // ---- steps ----
    val levels = IntArray(54) { (it * 7 + 1) % 5 }
    val st = P.Steps(12_345, 10_000, true, true, true, true, 0, 0, 2, levels)
    val f = P.steps(st)
    check(f.size == 3 + 39, "steps frame size")
    vec("STEPS_FULL", f, "steps=12345 goal=10000 f=15 label=0 hint=0 sub=2 lv=" + levels.joinToString(""))
    vec("STEPS_OFF", P.steps(P.Steps(0, 8000, false, false, false, false, 1, 1, 0, IntArray(54))), "steps=0 goal=8000 f=0 label=1 hint=1 sub=0 lv=" + "0".repeat(54))
    check(P.packLevels(IntArray(54) { 4 }).all { it.toInt() == 0x44 }, "levels pack two per byte")
    check(P.packLevels(intArrayOf(1, 2, 3, 9, -4)).take(3) == listOf<Byte>(0x21, 0x43, 0x00), "levels clamp and low nibble first")

    // ---- text ----
    check(P.ascii("Kabhi Kabhi Aditi", 48) == "Kabhi Kabhi Aditi", "plain text unchanged")
    check(P.ascii("Beyonc\u00E9 \u2013 It\u2019s \u201Cok\u201D\u2026", 48) == "Beyonce - It's \"ok\"...", "accents stripped, typographic punctuation made plain")
    check(P.ascii("\u0915\u092D\u0940 \u0915\u092D\u0940", 48) == "", "scripts the TFT cannot draw leave nothing")
    check(P.ascii("Song \u0915\u092D\u0940 Name \uD83D\uDE00", 48) == "Song Name", "unsupported characters dropped, spaces collapsed")
    check(P.ascii("  a   b  ", 48) == "a b" && P.ascii(null, 5) == "" && P.ascii("abcdefghij", 5) == "abcde", "trim, null, length limit")
    vec("MUSIC_PLAY", P.music(true, true, 102, 318, "Kabhi Kabhi Aditi", "Rashid Ali, Warbartan - Topic"), "sess=1 play=1 pos=102 dur=318 title=[Kabhi Kabhi Aditi] artist=[Rashid Ali, Warbartan - Topic]")
    vec("MUSIC_PAUSED", P.music(true, false, 5, 0, "Caf\u00E9 del Mar", "\u0915\u092D\u0940"), "sess=1 play=0 pos=5 dur=0 title=[Cafe del Mar] artist=[]")
    vec("MUSIC_NONE", P.music(false, false, 0, 0, null, null), "sess=0 play=0 pos=0 dur=0 title=[] artist=[]")
    vec("MUSIC_LONG", P.music(true, true, 65535, 65535, "T".repeat(80), "A".repeat(80)), "sess=1 play=1 pos=65535 dur=65535 title=[" + "T".repeat(48) + "] artist=[" + "A".repeat(40) + "]")

    // ---- wifi ----
    vec("WIFI_OK", P.wifiSet("HomeNet", "correct horse")!!, "ssid=[HomeNet] pass=[correct horse]")
    vec("WIFI_OPEN", P.wifiSet("Cafe", "")!!, "ssid=[Cafe] pass=[]")
    check(P.wifiSet("", "password1") == null && P.wifiSet("x".repeat(33), "password1") == null, "ssid length validated")
    check(P.wifiSet("ok", "short") == null && P.wifiSet("ok", "p".repeat(64)) == null, "password length validated")
    check(P.wifiSet("ok", "p".repeat(63)) != null && P.wifiSet("x".repeat(32), "12345678") != null, "boundary lengths accepted")

    // ---- chunking ----
    val big = P.music(true, true, 1, 2, "x".repeat(40), "y".repeat(30))
    val ch = P.chunks(big, 20)
    check(ch.all { it.size <= 20 } && ch.fold(ByteArray(0)) { a, b -> a + b }.contentEquals(big), "chunks cover the frame exactly")
    check(P.chunks(big, 5).all { it.size <= 20 }, "chunk size never below the minimum MTU payload")

    // ---- status ----
    val sb = byteArrayOf(1, 1, 0, 2, 2, 0, 0b10101, 1, 120, 0)
    val ps = P.parseStatus(sb)!!
    check(ps.screen == 2 && ps.wifiState == 2 && ps.timeValid && !ps.pairingOpen && ps.hasWifi && !ps.stepsFresh && ps.musicFresh && ps.bonds == 1 && ps.heapKb == 120, "status decoded")
    check(P.parseStatus(byteArrayOf(2, 1, 0, 0, 0, 0, 0, 0, 0, 0)) == null && P.parseStatus(ByteArray(3)) == null && P.parseStatus(null) == null, "bad status rejected")
    out.append("STATUS|${hex(sb)}|screen=2 wifi=2 reason=0 flags=21 bonds=1 heap=120\n")


    // ---- protocol additions: screen time, notifications, music extras, image transfer (the firmware test reads these vectors) ----
    val apps3 = listOf(P.AppUse("Instagram", 95), P.AppUse("YouTube", 70), P.AppUse("Chrome", 30))
    vec("SCREENTIME_FULL", P.screenTime(true, false, false, 222, 240, apps3), "has=1 perm=0 unavail=0 goalOn=1 total=222 goal=240 apps=Instagram:95,YouTube:70,Chrome:30")
    vec("SCREENTIME_PERM", P.screenTime(false, true, false, 0, 240, emptyList()), "has=0 perm=1 unavail=0 goalOn=1 total=0 goal=240 apps=")
    vec("SCREENTIME_NOGOAL", P.screenTime(true, false, false, 60, 0, listOf(P.AppUse("Maps", 60))), "has=1 perm=0 unavail=0 goalOn=0 total=60 goal=0 apps=Maps:60")
    vec("SCREENTIME_FOLD", P.screenTime(true, false, false, 8, 240, listOf(P.AppUse("Caf\u00E9 B\u00E4r \u00DCnicode Name", 5), P.AppUse("\u65E5\u672C\u8A9E", 3))), "has=1 perm=0 unavail=0 goalOn=1 total=8 goal=240 apps=Cafe Bar Unico:5,App:3")
    check(P.screenTime(true, false, false, 99999, 99999, List(9) { P.AppUse("A$it", 99999) }).size == 3 + 6 + 4 * (3 + 2), "screen time: numbers clamped to 24 h, at most 4 apps")
    vec("NOTIFS_LIST", P.notifs(true, false, listOf(P.Notif(0, "WhatsApp", "Aditi", "Are we still meeting at 6?"), P.Notif(14, "Gmail", "", "Your receipt from Cafe"))), "on=1 access=0 n=2 0:WhatsApp|Aditi|Are we still meeting at 6?;14:Gmail||Your receipt from Cafe")
    vec("NOTIFS_OFF", P.notifs(false, false, emptyList()), "on=0 access=0 n=0 ")
    vec("NOTIFS_NEEDS_ACCESS", P.notifs(true, true, emptyList()), "on=1 access=1 n=0 ")
    vec("NOTIFS_TRUNC", P.notifs(true, false, listOf(P.Notif(999_999, "Instagram", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmn", "x".repeat(80)))), "on=1 access=0 n=1 65535:Instagram|ABCDEFGHIJKLMNOPQRSTUVWX|" + "x".repeat(48))
    val many = P.notifs(true, false, List(8) { P.Notif(1, "a".repeat(12), "t".repeat(24), "x".repeat(48)) })
    check(many.size <= 203 && many[4].toInt() == 2, "notifications: only as many entries as fit one frame, newest first")
    vec("MUSIC2_LYRICS", P.music2(true, true, 2, 3, "SPOTIFY", "previous line", "the current line", "next line"), "art=1 sync=1 ly=2 idx=3 src=[SPOTIFY] prev=[previous line] cur=[the current line] next=[next line]")
    vec("MUSIC2_CAPTIONS", P.music2(false, false, 0, 0, "YOUTUBE", "", "", ""), "art=0 sync=0 ly=0 idx=0 src=[YOUTUBE] prev=[] cur=[] next=[]")
    vec("MUSIC2_OFF", P.music2(false, false, 4, 0, "SPOTIFY", "", "", ""), "art=0 sync=0 ly=4 idx=0 src=[SPOTIFY] prev=[] cur=[] next=[]")
    vec("MUSIC2_CAPTION_SETUP", P.music2(false, false, P.LY_CAPTION_SETUP, 0, "YOUTUBE", "", "", ""), "art=0 sync=0 ly=7 idx=0 src=[YOUTUBE] prev=[] cur=[] next=[]")
    vec("MUSIC2_CAPTION_WAIT", P.music2(false, false, P.LY_CAPTION_WAIT, 0, "BRAVE", "", "", ""), "art=0 sync=0 ly=8 idx=0 src=[BRAVE] prev=[] cur=[] next=[]")
    vec("MUSIC2_CAPTION_LIVE", P.music2(true, false, P.LY_AVAILABLE, 2, "YOUTUBE", "an earlier caption line", "the newest words being spoken", ""), "art=1 sync=0 ly=2 idx=2 src=[YOUTUBE] prev=[an earlier caption line] cur=[the newest words being spoken] next=[]")
    check(P.music2(false, false, 99, 0, null, null, "c".repeat(200), null).size <= 203, "music extras never exceed one frame")
    vec("IMG_BEGIN", P.imgBegin(7, P.KIND_PHOTO, 5000, 0xDEADBEEFL, 180, 240, 320, 3), "xfer=7 kind=2 total=5000 crc=deadbeef chunk=180 w=240 h=320 slot=3")
    vec("IMG_BEGIN_ART", P.imgBegin(8, 1, 12800, 0x12345678L, 180, 80, 80), "xfer=8 kind=1 total=12800 crc=12345678 chunk=180 w=80 h=80")
    vec("IMG_DATA", P.imgData(7, 300, byteArrayOf(0, 1, 2, 3, 4, 5), 1, 4), "xfer=7 seq=300 n=4 data=01020304")
    vec("IMG_END", P.imgEnd(7), "xfer=7")
    vec("IMG_CTRL_DELETE", P.deletePhoto(5), "op=4 slot=5")
    vec("GALLERY_CFG", P.galleryCfg(10, listOf(4, 0, 7)), "sec=10 n=3 order=4,0,7")
    vec("GALLERY_CFG_CLAMP", P.galleryCfg(1, listOf(9)), "sec=2 n=1 order=9")
    vec("GALLERY_CFG_EMPTY", P.galleryCfg(3600, emptyList()), "sec=3600 n=0 order=")
    vec("GALLERY_CFG_DUP", P.frame(P.MSG_GALLERY, byteArrayOf(1, 10, 0, 0, 2, 4, 4)), "REJECTED")
    vec("GALLERY_CFG_RANGE", P.frame(P.MSG_GALLERY, byteArrayOf(1, 10, 0, 0, 1, 24)), "REJECTED")
    vec("GALLERY_CFG_VER", P.frame(P.MSG_GALLERY, byteArrayOf(2, 10, 0, 0, 0)), "REJECTED")
    vec("GALLERY_CFG_LEN", P.frame(P.MSG_GALLERY, byteArrayOf(1, 10, 0, 0, 3, 4, 0)), "REJECTED")
    check(P.crc32("123456789".toByteArray()) == 0xCBF43926L, "crc32 check value")
    check(P.imgData(1, 0, ByteArray(180), 0, 180).size == 3 + 3 + 180, "a data chunk fits one frame")
    val sbx = byteArrayOf(1, 1, 0, 5, 2, 0, 0x15, 1, 120, 0, 1, 7, 0x2C, 0x01, 0, 0x26, 0x39, 0xF4.toByte(), 0xCB.toByte())
    val px = P.parseStatus(sbx)!!
    check(px.screen == 5 && px.hasExt && px.xferState == P.XFER_RECEIVING && px.xferId == 7 && px.xferNext == 300 && px.xferReason == 0 && px.photoCrc == 0xCBF43926L, "extended status decoded")
    check(!P.parseStatus(sb)!!.hasExt && P.parseStatus(sb)!!.photoCrc == 0L, "a 10-byte status from older firmware has no transfer fields")
    // ---- photo gallery (firmware 1.3) ----
    run {
        val b16 = P.imgBegin(3, P.KIND_ART, 12800, 0xCAFEBABEL, 190, 80, 80); check(b16.size == 3 + 16, "the album-art BEGIN is 16 bytes")
        val b17 = P.imgBegin(3, P.KIND_PHOTO, 5000, 0xCAFEBABEL, 190, 240, 320, 3); check(b17.size == 3 + 17 && b17[3 + 16].toInt() == 3 && b17[3 + 1].toInt() == P.KIND_PHOTO, "a photo BEGIN carries the slot as a 17th byte")
        check(P.imgBegin(3, P.KIND_PHOTO, 5000, 1L, 190, 240, 320, 99)[3 + 16].toInt() == P.PHOTO_SLOTS - 1, "the slot is clamped")
        check(P.screen(5).size == 3 + 1 && P.screen(P.SCREEN_GALLERY)[3].toInt() == 5, "showing the slideshow is the one-byte MSG_SCREEN")
        val dl = P.deletePhoto(6); check(dl.size == 3 + 2 && dl[3].toInt() == P.CTRL_DELETE_PHOTO && dl[4].toInt() == 6, "delete-photo control")
        val gc = P.galleryCfg(10, listOf(4, 0, 7, 4, 99, 24)); check(gc.size == 3 + 5 + 3 && gc[3].toInt() == 1 && gc[4].toInt() == 10 && gc[7].toInt() == 3 && gc[8].toInt() == 4 && gc[9].toInt() == 0 && gc[10].toInt() == 7, "slideshow setting drops duplicates and out-of-range slots")
        check(P.galleryCfg(0, emptyList())[4].toInt() == P.GAL_MIN_SEC && (P.galleryCfg(99999, emptyList())[4].toInt() and 0xFF) == (P.GAL_MAX_SEC and 0xFF), "the interval is clamped to 2..3600 seconds")
        check(P.photoDigest(emptyMap()) == 0L && P.photoDigest(mapOf(3 to 0xDEADBEEFL, 0 to 0x01020304L)) == 0x109439c6L && P.photoDigest(mapOf(0 to 0L)) == 0L, "photo digest matches the firmware")
        val gal = byteArrayOf(0x25, 0, 0, 0, 5, 0x0A, 0, 0xDC.toByte(), 0x05, 0x6C, 0x07, 0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte())   // mask, shown, interval, free KB, total KB, digest
        val st34 = sbx + gal; val g34 = P.parseStatus(st34)!!
        check(g34.hasGallery && g34.photoMask == 0x25L && g34.shownSlot == 5 && g34.intervalSec == 10 && g34.fsFreeKb == 1500 && g34.fsTotalKb == 1900 && g34.galDigest == 0xA1B2C3D4L && g34.photoCrc == 0xCBF43926L, "gallery status (19 + 15 bytes) is parsed")
        // firmware that also reports the album-art CRC (23 status bytes) puts the gallery bytes after byte 23: 38 bytes in all
        val st38 = sbx + byteArrayOf(0x44, 0x33, 0x22, 0x11) + gal; val g38 = P.parseStatus(st38)!!
        check(g38.hasGallery && g38.photoMask == 0x25L && g38.shownSlot == 5 && g38.intervalSec == 10 && g38.fsFreeKb == 1500 && g38.galDigest == 0xA1B2C3D4L, "gallery status after the album-art CRC (38 bytes) is parsed")
        check(!P.parseStatus(sbx)!!.hasGallery && !P.parseStatus(sbx + byteArrayOf(1, 2, 3, 4))!!.hasGallery, "a status without the gallery bytes (older firmware) has no gallery")
        out.append("STATUS_GAL|${hex(st34)}|gallery status\n")
    }
    out.append("STATUS_EXT|${hex(sbx)}|screen=5 receiving xfer=7 next=300 crc=cbf43926\n")
    extrasTests()

    if (args.isNotEmpty()) File(args[0]).writeText(out.toString())
    println("Device protocol tests: $passed checks passed" + (if (args.isNotEmpty()) ", vectors written" else ""))
}
