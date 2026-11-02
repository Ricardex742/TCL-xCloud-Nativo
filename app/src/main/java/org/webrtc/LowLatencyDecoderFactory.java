package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Bridge for the pinned WebRTC 1.1.1 codec wrapper. No vendor flags or reflection. */
public final class LowLatencyDecoderFactory implements VideoDecoderFactory {
  private final EglBase.Context context;
  private final boolean enabled;
  private final Consumer<String> status;
  private final DefaultVideoDecoderFactory standard;
  private final HardwareVideoDecoderFactory hardware;

  public LowLatencyDecoderFactory(EglBase.Context context, boolean enabled, Consumer<String> status) {
    this.context = context;
    this.enabled = enabled;
    this.status = status;
    standard = new DefaultVideoDecoderFactory(context);
    hardware = new HardwareVideoDecoderFactory(context);
  }

  @Override public VideoCodecInfo[] getSupportedCodecs() { return standard.getSupportedCodecs(); }

  @Override public VideoDecoder createDecoder(VideoCodecInfo codec) {
    VideoDecoder fallback = standard.createDecoder(codec);
    if (!enabled || !codec.getName().equalsIgnoreCase("H264")) return fallback;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      status.accept("Codec LL: não disponível nesta versão Android");
      return fallback;
    }
    try {
      // Ask WebRTC which hardware decoder it would normally use. This object is not initialized yet.
      VideoDecoder original = hardware.createDecoder(codec);
      if (original == null || fallback == null) return fallback;
      String name = original.getImplementationName();
      MediaCodecInfo chosen = null;
      for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
        if (!info.isEncoder() && info.getName().equals(name)) { chosen = info; break; }
      }
      if (chosen == null) return fallback;
      MediaCodecInfo.CodecCapabilities caps = chosen.getCapabilitiesForType("video/avc");
      if (!caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
        status.accept("Codec LL: driver não declara suporte; decoder padrão");
        return fallback;
      }
      Integer color = MediaCodecUtils.selectColorFormat(MediaCodecUtils.DECODER_COLOR_FORMATS, caps);
      if (color == null) return fallback;
      MediaCodecWrapperFactory wrappers = new MediaCodecWrapperFactoryImpl();
      VideoDecoder lowLatency = new AndroidVideoDecoder(
          codecName -> new LowLatencyCodec(wrappers.createByCodecName(codecName), status),
          name, VideoCodecMimeType.H264, color, context);
      status.accept("Codec LL: disponível, aguardando configuração");
      // Reuse the unmodified factory if the driver rejects the option or decoding fails.
      return new VideoDecoderFallback(fallback, lowLatency);
    } catch (RuntimeException error) {
      status.accept("Codec LL: indisponível; decoder padrão");
      return fallback;
    }
  }

  private static final class LowLatencyCodec implements MediaCodecWrapper {
    private final MediaCodecWrapper codec;
    private final Consumer<String> status;
    LowLatencyCodec(MediaCodecWrapper codec, Consumer<String> status) { this.codec = codec; this.status = status; }
    @Override public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
      try {
        codec.configure(format, surface, crypto, flags);
        status.accept("Codec LL: solicitado (suporte declarado pelo driver)");
      } catch (RuntimeException error) {
        status.accept("Codec LL: configuração rejeitada; retorno ao padrão");
        throw error;
      }
    }
    @Override public void start() { codec.start(); }
    @Override public void flush() { codec.flush(); }
    @Override public void stop() { codec.stop(); }
    @Override public void release() { codec.release(); }
    @Override public int dequeueInputBuffer(long timeoutUs) { return codec.dequeueInputBuffer(timeoutUs); }
    @Override public void queueInputBuffer(int index, int offset, int size, long timeUs, int flags) { codec.queueInputBuffer(index, offset, size, timeUs, flags); }
    @Override public int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeoutUs) { return codec.dequeueOutputBuffer(info, timeoutUs); }
    @Override public void releaseOutputBuffer(int index, boolean render) { codec.releaseOutputBuffer(index, render); }
    @Override public MediaFormat getInputFormat() { return codec.getInputFormat(); }
    @Override public MediaFormat getOutputFormat() { return codec.getOutputFormat(); }
    @Override public MediaFormat getOutputFormat(int index) { return codec.getOutputFormat(index); }
    @Override public ByteBuffer getInputBuffer(int index) { return codec.getInputBuffer(index); }
    @Override public ByteBuffer getOutputBuffer(int index) { return codec.getOutputBuffer(index); }
    @Override public Surface createInputSurface() { return codec.createInputSurface(); }
    @Override public void setParameters(Bundle params) { codec.setParameters(params); }
    @Override public MediaCodecInfo getCodecInfo() { return codec.getCodecInfo(); }
  }
}
