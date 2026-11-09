package org.webrtc;

/** GL-thread-only optional renderer; false means the caller must draw the ordinary image. */
public interface FsrUpscaler {
  void configure(int inputWidth, int inputHeight, int sharpness, int saturation);
  default void setColorAdjustments(int brightness, int contrast) {}
  boolean drawOes(int texture,float[] matrix,int frameWidth,int frameHeight,int x,int y,int width,int height);
  boolean drawRgb(int texture,float[] matrix,int frameWidth,int frameHeight,int x,int y,int width,int height);
  boolean drawYuv(int[] textures,float[] matrix,int frameWidth,int frameHeight,int x,int y,int width,int height);
  String diagnostics();
  void release();
}
