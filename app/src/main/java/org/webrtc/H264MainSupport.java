package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import java.util.HashMap;
import java.util.Map;

/** A Main offer is backed by the hardware decoder the WebRTC factory would actually use. */
public final class H264MainSupport {
  private H264MainSupport() {}
  public static int requiredLevel(int width,int height,int fps) {
    if(width<=0 || height<=0 || fps<=0 || width>8192 || height>8192 || fps>120) return -1;
    long blocks=((width+15L)/16)*((height+15L)/16), rate=blocks*fps;
    int[][] limits={{31,3600,108000},{32,5120,216000},{40,8192,245760},
        {41,8192,245760},{42,8704,522240},{50,22080,589824},{51,36864,983040}};
    for(int[] row:limits) if(blocks<=row[1] && rate<=row[2]) return row[0];
    return -1;
  }
  public static int androidLevel(int level) {
    switch(level) {
      case 31:return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
      case 32:return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
      case 40:return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
      case 41:return MediaCodecInfo.CodecProfileLevel.AVCLevel41;
      case 42:return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
      case 50:return MediaCodecInfo.CodecProfileLevel.AVCLevel5;
      case 51:return MediaCodecInfo.CodecProfileLevel.AVCLevel51;
      default:return Integer.MAX_VALUE;
    }
  }
  public static Map<String,String> parameters(int level) {
    if(androidLevel(level)==Integer.MAX_VALUE) throw new IllegalArgumentException("Unsupported AVC Main level");
    Map<String,String> params=new HashMap<>(H264Utils.getDefaultH264Params(false));
    params.put("profile-level-id",String.format(java.util.Locale.ROOT,"4d00%02x",level));
    return params;
  }
  public static VideoCodecInfo inspect(EglBase.Context context,int width,int height,int fps) {
    int required=requiredLevel(width,height,fps);
    if(required<0) return null;
    try {
      HardwareVideoDecoderFactory factory=new HardwareVideoDecoderFactory(context);
      String selected=null;
      for(VideoCodecInfo codec:factory.getSupportedCodecs()) {
        if(!codec.name.equalsIgnoreCase("H264")) continue;
        VideoDecoder decoder=factory.createDecoder(codec);
        if(decoder!=null) {
          selected=decoder.getImplementationName();
          // No codec is initialized here; this also releases future implementations safely.
          decoder.release();
          break;
        }
      }
      if(selected==null) return null;
      for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
        if(info.isEncoder() || !info.getName().equals(selected)) continue;
        MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType("video/avc");
        boolean main=false;
        for(MediaCodecInfo.CodecProfileLevel p:caps.profileLevels)
          if(p.profile==MediaCodecInfo.CodecProfileLevel.AVCProfileMain && p.level>=androidLevel(required)) main=true;
        if(main && caps.getVideoCapabilities()!=null &&
            caps.getVideoCapabilities().areSizeAndRateSupported(width,height,fps))
          return new VideoCodecInfo("H264",parameters(required),java.util.Collections.emptyList());
      }
    } catch(RuntimeException unavailable) { /* No reliable declaration means no experimental offer. */ }
    return null;
  }
}
