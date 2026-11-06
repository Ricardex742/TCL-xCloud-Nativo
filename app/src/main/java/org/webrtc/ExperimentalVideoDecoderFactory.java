package org.webrtc;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Hardware-only additions; the ordinary H.264 path keeps its existing recovery/fallback. */
public final class ExperimentalVideoDecoderFactory implements VideoDecoderFactory {
  private final VideoDecoderFactory base;
  private final EglBase.Context context;
  private final boolean lowLatency;
  private final DecoderRecoveryController recovery;
  private final Consumer<String> status, failure;
  private final String mode;
  private final int width, height, fps;
  private boolean probed;
  private ExperimentalDecoderSupport.Selection selected;
  private volatile String diagnostics = "Hardware experimental: não solicitado";

  public ExperimentalVideoDecoderFactory(EglBase.Context context, boolean lowLatency, DecoderRecoveryController recovery,
      Consumer<String> status, Consumer<String> failure, String mode, int width, int height, int fps) {
    this(new LowLatencyDecoderFactory(context,lowLatency,recovery,status),context,lowLatency,recovery,status,failure,mode,width,height,fps);
  }
  ExperimentalVideoDecoderFactory(VideoDecoderFactory base, EglBase.Context context, boolean lowLatency, DecoderRecoveryController recovery,
      Consumer<String> status, Consumer<String> failure, String mode, int width, int height, int fps) {
    this.context = context; this.lowLatency = lowLatency; this.recovery = recovery;
    this.status = status; this.failure = failure; this.mode = mode;
    this.width = width; this.height = height; this.fps = fps;
    this.base = base;
  }
  private synchronized ExperimentalDecoderSupport.Selection selection() {
    if (!probed) {
      probed = true;
      selected = ExperimentalDecoderSupport.find(mode,width,height,fps);
      if (!mode.equals("H264")) diagnostics = selected == null ?
          "Hardware experimental: indisponível para " + width + "×" + height + "@" + fps + "; H.264 disponível" :
          "Hardware experimental: " + selected.info.getName() + " • " + width + "×" + height + "@" + fps + " declarado";
    }
    return selected;
  }
  public String diagnostics() { return diagnostics; }
  @Override public VideoCodecInfo[] getSupportedCodecs() {
    ExperimentalDecoderSupport.Selection hardware = selection();
    List<VideoCodecInfo> codecs = new ArrayList<>();
    for (VideoCodecInfo codec : base.getSupportedCodecs()) {
      // HEVC is exposed only by the guarded hardware path, even if a platform advertises software HEVC.
      if (codec.name.equalsIgnoreCase("H265")) continue;
      if (mode.equals("H264_HIGH") && isHigh(codec)) continue;
      codecs.add(codec);
    }
    if (hardware != null) {
      if (mode.equals("HEVC")) {
        Map<String,String> params = new HashMap<>(MediaCodecUtils.getCodecProperties(VideoCodecMimeType.H265,false));
        params.put("profile-id","1"); params.put("tier-flag","0");
        params.put("level-id",Integer.toString(hardware.hevcLevelId()));
        codecs.add(new VideoCodecInfo("H265",params,Collections.emptyList()));
      } else codecs.add(H264Utils.DEFAULT_H264_HIGH_PROFILE_CODEC);
    }
    return codecs.toArray(new VideoCodecInfo[0]);
  }
  private static boolean isHigh(VideoCodecInfo codec) {
    String profile = codec.params.get("profile-level-id");
    return codec.name.equalsIgnoreCase("H264") && profile != null && profile.startsWith("64");
  }
  @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
    if (codec.name.equalsIgnoreCase("H265") &&
        (!codec.params.getOrDefault("profile-id","1").equals("1") || !codec.params.getOrDefault("tier-flag","0").equals("0"))) return null;
    boolean experimental = codec.name.equalsIgnoreCase("H265") || (mode.equals("H264_HIGH") && isHigh(codec));
    if (!experimental) return base.createDecoder(codec);
    ExperimentalDecoderSupport.Selection hardware = selection();
    if (hardware == null || !codec.name.equalsIgnoreCase(hardware.type.name())) return null;
    Supplier<VideoDecoder> normal = () -> new AndroidVideoDecoder(new MediaCodecWrapperFactoryImpl(),
        hardware.info.getName(),hardware.type,hardware.color,context);
    Supplier<VideoDecoder> preferred = lowLatency ? () -> new AndroidVideoDecoder(
        name -> new RealtimeDecoderCodec(new MediaCodecWrapperFactoryImpl().createByCodecName(name),hardware.lowLatency(),status),
        hardware.info.getName(),hardware.type,hardware.color,context) : normal;
    return new GuardedHardwareDecoder(new RecoverableVideoDecoder(preferred,normal,recovery),hardware::supports,failure);
  }
}
