package org.webrtc;

/** Optional GL renderer; false draws use the ordinary image. Status snapshots may be read by the UI. */
public interface FsrUpscaler {
  void configure(int inputWidth, int inputHeight, int sharpness, int saturation);
  default void setColorAdjustments(int brightness, int contrast) {}
  default void setTreatmentFinishing(int mode, boolean dither) {}
  boolean drawOes(int texture,float[] matrix,int frameWidth,int frameHeight,int x,int y,int width,int height);
  boolean drawRgb(int texture,float[] matrix,int frameWidth,int frameHeight,int x,int y,int width,int height);
  boolean drawYuv(int[] textures,float[] matrix,int frameWidth,int frameHeight,int x,int y,int width,int height);
  String diagnostics();
  /** No filtering is needed for the current frame; do not interpret zero filtered draws as GPU load. */
  default boolean isBypassed() { return false; }
  /** A persistent GPU/size rejection, distinct from a frame that needs no enlargement. */
  default String failureReason() { return null; }
  default int lastPassCount() { return 0; }
  void release();
}
