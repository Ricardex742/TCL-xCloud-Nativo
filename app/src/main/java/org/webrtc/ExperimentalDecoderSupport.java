package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.Locale;

/** Optional codecs must have a hardware decoder for the requested size/rate and 8-bit profile. */
final class ExperimentalDecoderSupport {
  static final class Selection {
    final MediaCodecInfo info;
    final MediaCodecInfo.CodecCapabilities caps;
    final VideoCodecMimeType type;
    final int color;
    final int fps;
    Selection(MediaCodecInfo info, MediaCodecInfo.CodecCapabilities caps, VideoCodecMimeType type, int color, int fps) {
      this.info = info; this.caps = caps; this.type = type; this.color = color; this.fps = fps;
    }
    boolean supports(int width, int height) {
      if (width <= 0 || height <= 0) return false;
      try { return caps.getVideoCapabilities() != null && caps.getVideoCapabilities().areSizeAndRateSupported(width, height, fps); }
      catch (RuntimeException error) { return false; }
    }
    boolean lowLatency() {
      try { return Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency); }
      catch (RuntimeException error) { return false; }
    }
  }

  static Selection find(String mode, int width, int height, int fps) {
    if (!mode.equals("HEVC") && !mode.equals("H264_HIGH")) return null;
    try {
      for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
        Selection selected = inspect(info, mode, width, height, fps);
        if (selected != null) return selected;
      }
    } catch (RuntimeException error) { /* No reliable capabilities means no experimental codec. */ }
    return null;
  }

  static Selection inspect(MediaCodecInfo info, String mode, int width, int height, int fps) {
    try {
      if (info.isEncoder() || !isHardware(info)) return null;
      VideoCodecMimeType type = mode.equals("HEVC") ? VideoCodecMimeType.H265 : VideoCodecMimeType.H264;
      if (!MediaCodecUtils.codecSupportsType(info, type)) return null;
      MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type.mimeType());
      boolean profile = false;
      for (MediaCodecInfo.CodecProfileLevel entry : caps.profileLevels) {
        if (type == VideoCodecMimeType.H265) {
          if (entry.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain &&
              entry.level >= MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31) profile = true;
        } else if ((entry.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh ||
            (Build.VERSION.SDK_INT >= 27 && entry.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh)) &&
            entry.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel31) profile = true;
      }
      if (!profile) return null;
      Integer color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps);
      if (color == null) return null;
      Selection selection = new Selection(info, caps, type, color, fps);
      return selection.supports(width, height) ? selection : null;
    } catch (RuntimeException error) { return null; }
  }

  private static boolean isHardware(MediaCodecInfo info) {
    if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated() && !info.isSoftwareOnly();
    // Android 7–9 lack a hardware flag; the vendor name is a conservative heuristic only.
    String name = info.getName().toLowerCase(Locale.ROOT);
    return (name.startsWith("omx.") || name.startsWith("c2.")) &&
        MediaCodecUtils.isHardwareAccelerated(info) && !MediaCodecUtils.isSoftwareOnly(info);
  }
}
