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
    check(hex(P.screen(2)) == "a5020102" && hex(P.screen(9)) == "a5020102" && hex(P.screen(-3)) == "a5020100", "screen is clamped")
    vec("HELLO", P.hello(), "proto=1")
    for (s in 0..2) vec("SCREEN", P.screen(s), "screen=$s")
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

    if (args.isNotEmpty()) File(args[0]).writeText(out.toString())
    println("Device protocol tests: $passed checks passed" + (if (args.isNotEmpty()) ", vectors written" else ""))
}
