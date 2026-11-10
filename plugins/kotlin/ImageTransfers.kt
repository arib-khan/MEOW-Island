package com.smartisland.app

/** One image transfer at a time over [DeviceLink.send] (gallery photos and album art); the status notifications from the device drive it. */
object ImageTransfers {
    private var active: ImageSender? = null
    private var activeKind = -1
    private var nextId = 1

    fun busy() = active != null
    fun activeKind() = if (active != null) activeKind else -1

    fun start(kind: Int, data: ByteArray, w: Int, h: Int, finish: (Boolean, Int, String) -> Unit, progress: (Int, Int) -> Unit, slot: Int = 0): Boolean {
        if (!DeviceLink.isReady()) return false
        active?.abort()                                              // a newer transfer replaces an older one (album art is only started while nothing else is sending)
        val seen = DeviceLink.lastStatus()?.xferId ?: 0
        do { nextId = if (nextId >= 255) 1 else nextId + 1 } while (nextId == seen)
        lateinit var s: ImageSender
        s = ImageSender(nextId, kind, data, w, h, { DeviceLink.send(it) },
            { ok, reason, msg -> if (active === s) { active = null; activeKind = -1 }; finish(ok, reason, msg) }, progress, slot)
        active = s; activeKind = kind
        s.start(System.currentTimeMillis())
        return true
    }

    fun onStatus(st: DeviceProtocol.Status) { active?.onStatus(st, System.currentTimeMillis()) }
    fun tick() { active?.tick(System.currentTimeMillis()) }
    fun cancelKind(kind: Int) { if (activeKind == kind) active?.abort() }
    fun onDisconnected() { active?.linkLost() }
}
