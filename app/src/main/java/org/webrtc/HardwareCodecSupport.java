package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Probes the exact hardware implementation selected by the pinned WebRTC factory, without starting it. */
public final class HardwareCodecSupport {
  private HardwareCodecSupport() {}
  public static final class Probe {
    public final VideoCodecInfo codec;
    public final String decoder;
    public final String status;
    Probe(VideoCodecInfo codec,String decoder,String status) { this.codec=codec;this.decoder=decoder;this.status=status; }
    public boolean available() { return codec!=null; }
    public String description() { return status+(decoder==null?"":" | "+decoder); }
  }
  private static Probe missing(String status) { return new Probe(null,null,status); }
  public static boolean basicProfile(VideoCodecInfo codec) {
    if(codec.name.equalsIgnoreCase("H265")) return "1".equals(codec.params.getOrDefault("profile-id","1"));
    if(codec.name.equalsIgnoreCase("VP9")) return "0".equals(codec.params.getOrDefault("profile-id","0"));
    if(codec.name.equalsIgnoreCase("AV1")) return "0".equals(codec.params.getOrDefault("profile","0"));
    return false;
  }
  public static Map<String,String> highParameters(int level,boolean constrained) {
    Map<String,String> p=new HashMap<>(H264MainSupport.parameters(level));
    p.put("profile-level-id",String.format(Locale.ROOT,constrained?"640c%02x":"6400%02x",level));
    return p;
  }
  public static Probe high(EglBase.Context context,int width,int height,int fps,boolean constrained) {
    int level=H264MainSupport.requiredLevel(width,height,fps);
    if(level<0) return missing("size/rate exceeds verified AVC levels");
    try {
      HardwareVideoDecoderFactory factory=new HardwareVideoDecoderFactory(context);
      for(VideoCodecInfo candidate:factory.getSupportedCodecs()) if(candidate.name.equalsIgnoreCase("H264")) {
        Probe p=verify(factory,candidate,"video/avc",MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
            H264MainSupport.androidLevel(level),width,height,fps);
        return p.available()?new Probe(new VideoCodecInfo("H264",highParameters(level,constrained),Collections.emptyList()),p.decoder,"AVC High hardware declared"):p;
      }
      return missing("H264 absent from hardware factory");
    } catch(RuntimeException unavailable) { return missing("hardware capability probe unavailable"); }
  }
  public static Probe alternative(EglBase.Context context,String name,int width,int height,int fps) {
    if(width<=0 || height<=0 || fps<=0) return missing("invalid dimensions");
    try {
      HardwareVideoDecoderFactory factory=new HardwareVideoDecoderFactory(context);
      for(VideoCodecInfo candidate:factory.getSupportedCodecs()) if(candidate.name.equalsIgnoreCase(name) && basicProfile(candidate)) {
        String mime;int profile;
        switch(name.toUpperCase(Locale.ROOT)) {
          case "H265":mime="video/hevc";profile=MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;break;
          case "VP9":mime="video/x-vnd.on2.vp9";profile=MediaCodecInfo.CodecProfileLevel.VP9Profile0;break;
          case "AV1":mime="video/av01";profile=1;break; // AV1ProfileMain8 (API 29 constant), no API 29 call.
          default:return missing("unsupported experiment");
        }
        return verify(factory,candidate,mime,profile,0,width,height,fps);
      }
      return missing(name+" absent from hardware factory");
    } catch(RuntimeException unavailable) { return missing("hardware capability probe unavailable"); }
  }
  private static Probe verify(HardwareVideoDecoderFactory factory,VideoCodecInfo candidate,String mime,
      int profile,int level,int width,int height,int fps) {
    VideoDecoder decoder=factory.createDecoder(candidate);
    if(decoder==null) return missing("hardware decoder not created");
    String selected;
    try { selected=decoder.getImplementationName(); } finally { decoder.release(); }
    for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
      if(info.isEncoder() || !info.getName().equals(selected)) continue;
      if(!MediaCodecUtils.isHardwareAccelerated(info) || MediaCodecUtils.isSoftwareOnly(info)) return missing("software decoder excluded");
      MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType(mime);
      boolean declared=false;
      for(MediaCodecInfo.CodecProfileLevel p:caps.profileLevels) if(p.profile==profile && p.level>=level) declared=true;
      if(!declared) return new Probe(null,selected,"profile/level not declared");
      if(caps.getVideoCapabilities()==null || !caps.getVideoCapabilities().areSizeAndRateSupported(width,height,fps))
        return new Probe(null,selected,"requested size/rate not declared");
      return new Probe(candidate,selected,"hardware profile and size/rate declared");
    }
    return missing("selected hardware implementation not found");
  }
}
