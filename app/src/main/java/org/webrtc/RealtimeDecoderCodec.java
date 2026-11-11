package org.webrtc;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Configuration hints for the pinned Java decoder; no frame copies or vendor parameters. */
final class RealtimeDecoderCodec implements MediaCodecWrapper {
  private final MediaCodecWrapper codec;
  private final boolean lowLatency;
  private final Consumer<String> status;

  RealtimeDecoderCodec(MediaCodecWrapper codec, boolean lowLatency, Consumer<String> status) {
    this.codec = codec; this.lowLatency = lowLatency; this.status = status;
  }

  @Override public void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) {
    // KEY_PRIORITY=0 requests realtime resources. It is independent of FEATURE_LowLatency.
    format.setInteger(MediaFormat.KEY_PRIORITY, 0);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lowLatency) {
      format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
    }
    try {
      codec.configure(format, surface, crypto, flags);
    } catch (RuntimeException error) {
      status.accept("Codec: opções de tempo real rejeitadas; retorno ao padrão");
      // The pinned AndroidVideoDecoder releases and returns a failure status for this exception.
      throw new IllegalStateException("Realtime decoder configuration rejected", error);
    }
  }

  @Override public void start() {
    try {
      codec.start();
      status.accept("Codec: prioridade de tempo real solicitada | LL: " +
          (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lowLatency
              ? "solicitado (suporte declarado)" : "não solicitado (sem suporte declarado/API)"));
    } catch (RuntimeException error) {
      status.accept("Codec: início com opções de tempo real rejeitado; retorno ao padrão");
      throw new IllegalStateException("Realtime decoder start rejected", error);
    }
  }

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
