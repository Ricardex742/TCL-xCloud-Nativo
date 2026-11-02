package org.webrtc;

import java.util.concurrent.atomic.AtomicReference;

/** Cross-thread request only: the actual reset runs on WebRTC's decoder thread, at an IDR. */
public final class DecoderRecoveryController {
  public interface ResetRequest { void request(); }
  private final AtomicReference<ResetRequest> active = new AtomicReference<>();
  void attach(ResetRequest request) { active.set(request); }
  void detach(ResetRequest request) { active.compareAndSet(request, null); }
  public boolean requestReset() {
    ResetRequest request = active.get();
    if (request == null) return false;
    request.request();
    return true;
  }
}
