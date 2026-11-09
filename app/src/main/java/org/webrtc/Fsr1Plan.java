package org.webrtc;

/** Bound FSR working storage before any texture allocation. No change to stream/input coordinates. */
public final class Fsr1Plan {
  public static final String NO_UPSCALING="sem ampliação; escala padrão";
  public static final long MAX_PIXELS=3840L*2160;
  public static final long MAX_BYTES=64L*1024*1024;
  public final int inputWidth,inputHeight,outputWidth,outputHeight;
  public final long workingBytes;
  public final String bypass;
  private Fsr1Plan(int iw,int ih,int ow,int oh,String bypass) {
    inputWidth=iw;inputHeight=ih;outputWidth=ow;outputHeight=oh;this.bypass=bypass;
    workingBytes=bypass==null?4L*((long)iw*ih+(long)ow*oh):0;
  }
  public boolean active() { return bypass==null; }
  public static Fsr1Plan create(int iw,int ih,int ow,int oh,int maxTexture,int maxRenderbuffer,int precision) {
    String reason=null;
    if(iw<=0 || ih<=0 || ow<=0 || oh<=0 || iw>16384 || ih>16384) reason="dimensões inválidas";
    else if(precision<23) reason="GPU sem precisão highp de 23 bits";
    else if(ow>maxTexture || oh>maxTexture || ow>maxRenderbuffer || oh>maxRenderbuffer) reason="limite de textura da GPU";
    else if((long)ow*oh>MAX_PIXELS) reason="saída acima do limite 4K";
    else if(ow<=iw && oh<=ih) reason=NO_UPSCALING;
    // If aspect selection reduces one axis, do that reduction during RGB preparation.
    int inputW=Math.min(Math.max(0,iw),Math.max(0,ow)),inputH=Math.min(Math.max(0,ih),Math.max(0,oh));
    if(reason==null && 4L*((long)inputW*inputH+(long)ow*oh)>MAX_BYTES) reason="limite de memória do filtro";
    return new Fsr1Plan(inputW,inputH,ow,oh,reason);
  }
  public static float rcasStrength(int percent) {
    return percent<=0?0f:(float)Math.pow(2.0,-2.0*(1.0-Math.min(100,percent)/100.0));
  }
}
