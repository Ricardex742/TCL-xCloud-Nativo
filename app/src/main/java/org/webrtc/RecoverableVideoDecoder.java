package org.webrtc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Reset the hardware decoder safely when an encoder restarts or replaces SPS/resolution. */
public final class RecoverableVideoDecoder implements VideoDecoder {
  private final Supplier<VideoDecoder> primary;
  private final Supplier<VideoDecoder> standard;
  private final DecoderRecoveryController controller;
  private final AtomicBoolean resetRequested = new AtomicBoolean();
  private final DecoderRecoveryController.ResetRequest request = () -> resetRequested.set(true);
  private volatile VideoDecoder decoder;
  private Settings settings;
  private Callback callback;
  private boolean released;
  private boolean initialized;
  private boolean standardOnly;

  public RecoverableVideoDecoder(Supplier<VideoDecoder> primary, Supplier<VideoDecoder> standard,
                                 DecoderRecoveryController controller) {
    this.primary = primary; this.standard = standard; this.controller = controller;
    decoder = primary.get();
  }
  @Override public VideoCodecStatus initDecode(Settings settings, Callback callback) {
    this.settings = settings; this.callback = callback; released = false;
    initialized = false;
    controller.detach(request);
    if (decoder == null) return VideoCodecStatus.FALLBACK_SOFTWARE;
    VideoCodecStatus result = decoder.initDecode(settings, callback);
    if (result != VideoCodecStatus.OK) {
      if (standardOnly || releaseCurrent() != VideoCodecStatus.OK) return result;
      standardOnly = true;
      decoder = standard.get();
      if (decoder == null) return VideoCodecStatus.FALLBACK_SOFTWARE;
      result = decoder.initDecode(settings, callback);
    }
    initialized = result == VideoCodecStatus.OK;
    if (initialized) controller.attach(request);
    return result;
  }
  @Override public VideoCodecStatus decode(EncodedImage image, DecodeInfo info) {
    if (released || !initialized || decoder == null) return VideoCodecStatus.UNINITIALIZED;
    if (image.frameType == EncodedImage.FrameType.VideoFrameKey && resetRequested.compareAndSet(true, false)) {
      initialized = false;
      controller.detach(request);
      if (releaseCurrent() != VideoCodecStatus.OK) return VideoCodecStatus.FALLBACK_SOFTWARE;
      decoder = standardOnly ? standard.get() : primary.get();
      if (decoder == null) return VideoCodecStatus.FALLBACK_SOFTWARE;
      Settings next = new Settings(settings.numberOfCores,
          image.encodedWidth > 0 ? image.encodedWidth : settings.width,
          image.encodedHeight > 0 ? image.encodedHeight : settings.height);
      if (initDecode(next, callback) != VideoCodecStatus.OK) return VideoCodecStatus.FALLBACK_SOFTWARE;
    }
    return decoder.decode(image, info);
  }
  @Override public VideoCodecStatus release() {
    if (released) return VideoCodecStatus.OK;
    released = true; initialized = false;
    controller.detach(request); resetRequested.set(false);
    return releaseCurrent();
  }
  private VideoCodecStatus releaseCurrent() {
    VideoDecoder previous = decoder;
    decoder = null;
    return previous == null ? VideoCodecStatus.OK : previous.release();
  }
  @Override public String getImplementationName() {
    VideoDecoder current = decoder;
    return current == null ? "decoder" : current.getImplementationName();
  }
}
