package org.webrtc;

import java.util.Arrays;
import java.util.function.Consumer;

/** Advertises real H.264 capabilities only; Baseline never forges a profile or level. */
public final class H264VideoDecoderFactory implements VideoDecoderFactory {
  private final VideoDecoderFactory base;
  private final boolean baselineOnly;

  public H264VideoDecoderFactory(EglBase.Context context, boolean lowLatency,
      DecoderRecoveryController recovery, Consumer<String> status, boolean baselineOnly) {
    this(new LowLatencyDecoderFactory(context, lowLatency, recovery, status), baselineOnly);
  }
  H264VideoDecoderFactory(VideoDecoderFactory base, boolean baselineOnly) {
    this.base = base;
    this.baselineOnly = baselineOnly;
  }
  private boolean accepts(VideoCodecInfo codec) {
    String profile = codec.params.get("profile-level-id");
    return codec.name.equalsIgnoreCase("H264") && (!baselineOnly ||
        (profile != null && profile.matches("42[0-9a-fA-F]{4}")));
  }
  @Override public VideoCodecInfo[] getSupportedCodecs() {
    return Arrays.stream(base.getSupportedCodecs()).filter(this::accepts).toArray(VideoCodecInfo[]::new);
  }
  @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
    return accepts(codec) ? base.createDecoder(codec) : null;
  }
}
