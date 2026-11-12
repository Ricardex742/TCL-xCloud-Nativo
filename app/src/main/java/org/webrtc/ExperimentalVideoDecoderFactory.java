package org.webrtc;

import java.util.*;
import java.util.function.Consumer;

/** Opt-in receive experiments. Extra families are hardware-only; H264 Main/Baseline remain available. */
public final class ExperimentalVideoDecoderFactory implements VideoDecoderFactory {
  private final VideoDecoderFactory base;
  private final HardwareVideoDecoderFactory hardware;
  private final DecoderRecoveryController recovery;
  private final List<VideoCodecInfo> extras;
  private final boolean experimental;
  private final String diagnostic;
  private final Consumer<String> status;
  public ExperimentalVideoDecoderFactory(EglBase.Context context,boolean lowLatency,DecoderRecoveryController recovery,
      Consumer<String> status,String mode,int width,int height) {
    this.recovery=recovery;this.status=status;hardware=new HardwareVideoDecoderFactory(context);
    experimental=mode.equals("H264_HIGH_HW") || mode.equals("H265") || mode.equals("VP9") || mode.equals("AV1");
    base=new H264VideoDecoderFactory(context,lowLatency,recovery,status,mode.equals("H264_BASELINE"),
        mode.equals("H264_MAIN") || experimental,width,height);
    extras=new ArrayList<>();
    if(mode.equals("H264_HIGH_HW")) {
      HardwareCodecSupport.Probe high=HardwareCodecSupport.high(context,width,height,60,false);
      if(high.available()) {
        extras.add(high.codec);
        extras.add(new VideoCodecInfo("H264",HardwareCodecSupport.highParameters(H264MainSupport.requiredLevel(width,height,60),true),Collections.emptyList()));
      }
      diagnostic=high.description();
    } else if(experimental) {
      HardwareCodecSupport.Probe probe=HardwareCodecSupport.alternative(context,mode,width,height,60);
      if(probe.available()) extras.add(probe.codec);
      diagnostic=probe.description();
    } else diagnostic="standard H264 negotiation";
  }
  // Deterministic factory composition for negotiation/decoder routing tests; no codec is initialized.
  ExperimentalVideoDecoderFactory(VideoDecoderFactory base,HardwareVideoDecoderFactory hardware,
      DecoderRecoveryController recovery,List<VideoCodecInfo> extras) {
    this.base=base;this.hardware=hardware;this.recovery=recovery;this.extras=extras;
    experimental=true;diagnostic="test capabilities";status=value -> {};
  }
  public String diagnostics() { return diagnostic; }
  @Override public VideoCodecInfo[] getSupportedCodecs() {
    List<VideoCodecInfo> result=new ArrayList<>(extras);
    for(VideoCodecInfo codec:base.getSupportedCodecs()) {
      if(!experimental || codec.name.equalsIgnoreCase("H264") && !codec.params.getOrDefault("profile-level-id","").startsWith("64")) result.add(codec);
    }
    return result.toArray(new VideoCodecInfo[0]);
  }
  private boolean extra(VideoCodecInfo codec) {
    for(VideoCodecInfo offered:extras) {
      if(!offered.name.equalsIgnoreCase(codec.name)) continue;
      if(!offered.name.equalsIgnoreCase("H264")) return HardwareCodecSupport.basicProfile(codec);
      String actual=codec.params.getOrDefault("profile-level-id","");
      String expected=offered.params.getOrDefault("profile-level-id","");
      if(actual.matches("[0-9a-fA-F]{6}") && expected.length()==6 && expected.substring(0,4).equalsIgnoreCase(actual.substring(0,4))) return true;
    }
    return false;
  }
  @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
    if(codec.name.equalsIgnoreCase("H264")) {
      if(experimental && codec.params.getOrDefault("profile-level-id","").startsWith("64") && !extra(codec)) return null;
      return base.createDecoder(codec);
    }
    if(!extra(codec) || hardware==null) return null;
    status.accept("Codec: "+codec.name+" hardware; realtime codec options not requested");
    return new RecoverableVideoDecoder(() -> hardware.createDecoder(codec),() -> hardware.createDecoder(codec),recovery);
  }
}
