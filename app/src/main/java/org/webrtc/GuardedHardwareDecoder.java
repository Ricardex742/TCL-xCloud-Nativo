package org.webrtc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Never feeds a rejected HEVC/High stream into an H.264 baseline or software decoder. */
final class GuardedHardwareDecoder implements VideoDecoder {
  private final VideoDecoder delegate;
  private final BiPredicate<Integer,Integer> supports;
  private final Consumer<String> failure;
  private final AtomicBoolean failed = new AtomicBoolean();
  private int width, height;
  GuardedHardwareDecoder(VideoDecoder delegate, BiPredicate<Integer,Integer> supports, Consumer<String> failure) {
    this.delegate = delegate; this.supports = supports; this.failure = failure;
  }
  private boolean checkSize(int w, int h) {
    if (w == width && h == height && w > 0 && h > 0) return true;
    if (!supports.test(w,h)) { reject("dimensões/taxa não suportadas pelo hardware: " + w + "×" + h); return false; }
    width = w; height = h; return true;
  }
  private void reject(String detail) { if (failed.compareAndSet(false,true)) failure.accept("VIDEO_CODEC: " + detail); }
  @Override public VideoCodecStatus initDecode(Settings settings, Callback callback) {
    if (failed.get() || !checkSize(settings.width,settings.height)) return VideoCodecStatus.ERR_SIZE;
    try {
      VideoCodecStatus result = delegate.initDecode(settings,callback);
      if (result != VideoCodecStatus.OK) reject("decoder experimental rejeitou a inicialização: " + result);
      return result;
    } catch (RuntimeException error) { reject("falha ao iniciar decoder experimental"); return VideoCodecStatus.ERROR; }
  }
  @Override public VideoCodecStatus decode(EncodedImage image, DecodeInfo info) {
    if (failed.get()) return VideoCodecStatus.ERROR;
    if (image.encodedWidth > 0 && image.encodedHeight > 0 && !checkSize(image.encodedWidth,image.encodedHeight)) return VideoCodecStatus.ERR_SIZE;
    try {
      VideoCodecStatus result = delegate.decode(image,info);
      if (result.getNumber() < 0) reject("decoder experimental falhou: " + result);
      return result;
    } catch (RuntimeException error) { reject("falha ao decodificar vídeo experimental"); return VideoCodecStatus.ERROR; }
  }
  @Override public VideoCodecStatus release() { return delegate.release(); }
  @Override public String getImplementationName() { return delegate.getImplementationName(); }
}
