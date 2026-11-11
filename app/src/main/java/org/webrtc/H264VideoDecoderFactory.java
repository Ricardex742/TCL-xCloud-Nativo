package org.webrtc;

import java.util.Arrays;
import java.util.function.Consumer;

/** Advertises real H.264 capabilities only; Baseline never forges a profile or level. */
public final class H264VideoDecoderFactory implements VideoDecoderFactory {
  private final VideoDecoderFactory base;
  private final boolean baselineOnly;
  private final VideoCodecInfo main;

  public H264VideoDecoderFactory(EglBase.Context context, boolean lowLatency,
      DecoderRecoveryController recovery, Consumer<String> status, boolean baselineOnly) {
    this(new LowLatencyDecoderFactory(context, lowLatency, recovery, status), baselineOnly);
  }
  public H264VideoDecoderFactory(EglBase.Context context, boolean lowLatency,
      DecoderRecoveryController recovery, Consumer<String> status, boolean baselineOnly,
      boolean requestMain,int width,int height) {
    this(new LowLatencyDecoderFactory(context,lowLatency,recovery,status),baselineOnly,
        requestMain && !baselineOnly ? H264MainSupport.inspect(context,width,height,60) : null);
  }
  H264VideoDecoderFactory(VideoDecoderFactory base, boolean baselineOnly) {
    this(base,baselineOnly,null);
  }
  H264VideoDecoderFactory(VideoDecoderFactory base,boolean baselineOnly,VideoCodecInfo main) {
    this.base = base;
    this.baselineOnly = baselineOnly;
    this.main = baselineOnly ? null : main;
  }
  private boolean accepts(VideoCodecInfo codec) {
    String profile = codec.params.get("profile-level-id");
    return codec.name.equalsIgnoreCase("H264") && (!baselineOnly ||
        (profile != null && profile.matches("42[0-9a-fA-F]{4}")));
  }
  @Override public VideoCodecInfo[] getSupportedCodecs() {
    java.util.stream.Stream<VideoCodecInfo> codecs=Arrays.stream(base.getSupportedCodecs()).filter(this::accepts);
    return (main==null ? codecs : java.util.stream.Stream.concat(java.util.stream.Stream.of(main),codecs))
        .toArray(VideoCodecInfo[]::new);
  }
  @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
    String profile=codec.params.get("profile-level-id");
    if(profile!=null && profile.toLowerCase(java.util.Locale.ROOT).startsWith("4d") && main==null) return null;
    return accepts(codec) ? base.createDecoder(codec) : null;
  }
}
