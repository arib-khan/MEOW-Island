// Receiver side of the image transfer protocol (BEGIN / DATA / END / CTRL). Pure C++: all storage goes through RxSink, so the same state machine
// runs on the ESP32 (LittleFS file per photo, RAM block for album art) and in the desktop tests (in-memory sink, lossy channel).
//
// Rules the phone can rely on
//   * chunks are accepted strictly in order (seq == nextSeq); duplicates, gaps and chunks of another transfer are dropped silently;
//     the phone learns the truth from the status extension (nextSeq) and resends from there
//   * nothing replaces a stored photo until the whole file arrived, its CRC-32 matched and its structure validated (a complete JPEG decode); on any
//     failure the previous photo in that slot stays untouched
//   * a BEGIN whose CRC equals the photo already stored in that slot answers DONE immediately and writes nothing (no repeated flash writes)
//   * a transfer that goes quiet for RX_TIMEOUT_MS is abandoned
#pragma once
#include "protocol.h"
#include "crc32.h"

namespace meow {

constexpr uint32_t RX_TIMEOUT_MS = 6000;

struct RxSink {
  virtual ~RxSink() {}
  // ---- photos (kind 2), one flash file per slot
  virtual uint32_t storedPhotoCrc(uint8_t slot) = 0;                                          // CRC-32 of the file in that slot, 0 = empty
  virtual bool openPhoto(uint8_t slot, uint32_t total, uint8_t& reason) = 0;                  // prepare a temp file; false if there is no room
  virtual bool writePhoto(const uint8_t* d, size_t n) = 0;
  virtual bool commitPhoto(uint8_t slot, uint32_t crc, uint8_t& reason) = 0;                  // validate + publish; false keeps the old photo
  virtual void abortPhoto() = 0;
  virtual void deletePhoto(uint8_t slot) = 0;
  // ---- album art (kind 1), a RAM block
  virtual uint32_t storedArtCrc() = 0;                                                        // CRC-32 of the art in RAM, 0 = none
  virtual bool openArt(uint32_t total, uint16_t w, uint16_t h, uint8_t& reason) = 0;
  virtual bool writeArt(const uint8_t* d, size_t n) = 0;
  virtual bool commitArt(uint32_t crc, uint8_t& reason) = 0;
  virtual void abortArt() = 0;
  virtual void clearArt() = 0;
};

class ImageReceiver {
 public:
  ImageReceiver(RxSink* sink, uint16_t photoW, uint16_t photoH) : sink_(sink), pw_(photoW), ph_(photoH) {}

  void onBegin(const ImgBegin& b, uint32_t now) {
    if (state_ == XFER_RECEIVING && b.xfer == id_ && kind_ == b.kind && total_ == b.total && crcWant_ == b.crc && (b.kind != KIND_PHOTO || b.slot == slot_)) { last_ = now; return; }   // a repeated BEGIN keeps the progress
    if (state_ == XFER_RECEIVING) { abortSink(); state_ = XFER_IDLE; }
    id_ = b.xfer; kind_ = b.kind; total_ = b.total; crcWant_ = b.crc; got_ = 0; next_ = 0; crc_ = crc32Begin(); reason_ = XR_NONE; last_ = now; slot_ = b.kind == KIND_PHOTO ? b.slot : 0;
    uint8_t why = XR_FLASH;
    if (b.kind == KIND_PHOTO) {
      if (b.total < PHOTO_MIN_FILE || b.total > PHOTO_MAX_FILE || b.w != pw_ || b.h != ph_ || b.slot >= PHOTO_SLOTS) return fail(b.total > PHOTO_MAX_FILE ? XR_TOO_LARGE : XR_BAD_HEADER);
      if (sink_->storedPhotoCrc(b.slot) != 0 && sink_->storedPhotoCrc(b.slot) == b.crc) { state_ = XFER_DONE; return; }   // this slot already holds exactly this photo
      if (!sink_->openPhoto(b.slot, b.total, why)) return fail(why);
    } else {
      if (b.w == 0 || b.h == 0 || b.w > ART_MAX_SIDE || b.h > ART_MAX_SIDE || b.total != (uint32_t)b.w * b.h * 2) return fail(XR_BAD_HEADER);
      if (!sink_->openArt(b.total, b.w, b.h, why)) return fail(why);
    }
    state_ = XFER_RECEIVING;
  }

  void onData(const uint8_t* p, uint8_t len, uint32_t now) {
    if (state_ != XFER_RECEIVING || len < 3 || p[0] != id_) return;
    uint16_t seq = rd16(p + 1);
    if (seq != next_) return;                                                       // duplicate or gap: ignore, the status tells the phone where we are
    size_t n = len - 3;
    if (n == 0 || got_ + n > total_) return fail(XR_SEQUENCE);
    if (!(kind_ == KIND_PHOTO ? sink_->writePhoto(p + 3, n) : sink_->writeArt(p + 3, n))) return fail(XR_FLASH);
    crc_ = crc32Update(crc_, p + 3, n); got_ += n; next_++; last_ = now;
  }

  void onEnd(const uint8_t* p, uint8_t len) {
    if (state_ != XFER_RECEIVING || len != 1 || p[0] != id_) return;
    if (got_ != total_) return fail(XR_SEQUENCE);
    if (crc32End(crc_) != crcWant_) return fail(XR_CRC);
    uint8_t why = XR_BAD_IMAGE;
    if (!(kind_ == KIND_PHOTO ? sink_->commitPhoto(slot_, crcWant_, why) : sink_->commitArt(crcWant_, why))) return fail(why);
    state_ = XFER_DONE;
  }

  void onCtrl(uint8_t op) {
    if (op == CTRL_ABORT) { if (state_ == XFER_RECEIVING) abortSink(); state_ = XFER_IDLE; reason_ = XR_NONE; }
    else if (op == CTRL_CLEAR_ART) sink_->clearArt();
  }
  // CTRL [4][slot]: removes one photo. A transfer into that slot is abandoned first.
  void onDeletePhoto(uint8_t slot) {
    if (slot >= PHOTO_SLOTS) return;
    if (state_ == XFER_RECEIVING && kind_ == KIND_PHOTO && slot_ == slot) { sink_->abortPhoto(); state_ = XFER_IDLE; }
    sink_->deletePhoto(slot);
  }

  void service(uint32_t now) { if (state_ == XFER_RECEIVING && (uint32_t)(now - last_) > RX_TIMEOUT_MS) fail(XR_SEQUENCE); }

  XferStatus status() const { XferStatus s; s.state = state_; s.id = id_; s.reason = reason_; s.nextSeq = next_; s.photoCrc = kind_ == KIND_PHOTO ? sink_->storedPhotoCrc(slot_) : 0; s.artCrc = sink_->storedArtCrc(); return s; }
  uint8_t state() const { return state_; }
  uint8_t kind() const { return kind_; }

 private:
  void abortSink() { if (kind_ == KIND_PHOTO) sink_->abortPhoto(); else sink_->abortArt(); }
  void fail(uint8_t why) { if (state_ == XFER_RECEIVING) abortSink(); state_ = XFER_FAILED; reason_ = why; }
  RxSink* sink_; uint16_t pw_, ph_;
  uint8_t state_ = XFER_IDLE, id_ = 0, kind_ = 0, reason_ = XR_NONE, slot_ = 0; uint32_t total_ = 0, got_ = 0, crcWant_ = 0, crc_ = 0, last_ = 0; uint16_t next_ = 0;
};

}  // namespace meow
