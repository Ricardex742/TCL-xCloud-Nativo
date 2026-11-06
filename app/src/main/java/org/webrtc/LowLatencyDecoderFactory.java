package org.webrtc;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bridge for the pinned WebRTC 1.1.1 codec wrapper. No vendor flags or reflection. */
public final class LowLatencyDecoderFactory implements VideoDecoderFactory {
  private final EglBase.Context context;
  private final boolean enabled;
  private final Consumer<String> status;
  private final DefaultVideoDecoderFactory standard;
  private final HardwareVideoDecoderFactory hardware;
  private final DecoderRecoveryController recovery;

  public LowLatencyDecoderFactory(EglBase.Context context, boolean enabled, DecoderRecoveryController recovery, Consumer<String> status) {
    this.context = context;
    this.enabled = enabled;
    this.status = status;
    this.recovery = recovery;
    standard = new DefaultVideoDecoderFactory(context);
    hardware = new HardwareVideoDecoderFactory(context);
  }

  @Override public VideoCodecInfo[] getSupportedCodecs() { return standard.getSupportedCodecs(); }

  @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
    VideoDecoder fallback = standard.createDecoder(codec);
    VideoDecoder original = hardware.createDecoder(codec);
    if (original == null || fallback == null) return fallback;
    Supplier<VideoDecoder> normal = () -> hardware.createDecoder(codec);
    Supplier<VideoDecoder> preferred = normal;
    if (enabled && codec.getName().equalsIgnoreCase("H264")) {
      try {
        String name = original.getImplementationName();
        MediaCodecInfo chosen = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
          if (!info.isEncoder() && info.getName().equals(name)) { chosen = info; break; }
        }
        if (chosen != null) {
          MediaCodecInfo.CodecCapabilities caps = chosen.getCapabilitiesForType("video/avc");
          Integer color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps);
          if (color != null) {
            boolean lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
            MediaCodecWrapperFactory wrappers = new MediaCodecWrapperFactoryImpl();
            preferred = () -> new AndroidVideoDecoder(
                codecName -> new RealtimeDecoderCodec(wrappers.createByCodecName(codecName), lowLatency, status),
                name, VideoCodecMimeType.H264, color, context);
            normal = () -> {
              status.accept("Codec: padrão após rejeição das opções de tempo real");
              return hardware.createDecoder(codec);
            };
            status.accept("Codec: aguardando configuração de tempo real");
          } else status.accept("Codec: formato não disponível; decoder padrão");
        } else status.accept("Codec: capacidades não encontradas; decoder padrão");
      } catch (RuntimeException error) {
        status.accept("Codec: capacidades indisponíveis; decoder padrão");
      }
    }
    // Only raw Java hardware decoders can be delegated here. Native wrappers stay outside.
    return new VideoDecoderFallback(fallback, new RecoverableVideoDecoder(preferred, normal, recovery));
  }

}
