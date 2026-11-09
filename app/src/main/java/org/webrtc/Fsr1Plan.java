package org.webrtc;

/** Bound FSR working storage before allocation; presentation buffers are accounted separately. */
public final class Fsr1Plan {
  public static final String NO_UPSCALING="sem ampliação; escala padrão";
  public static final long MAX_PIXELS=3840L*2160;
  public static final long MAX_BYTES=64L*1024*1024;
  public final int inputWidth,inputHeight,outputWidth,outputHeight;
  public final boolean directInput,nativeRcas;
  public final long workingBytes;
  public final String bypass;
  private Fsr1Plan(int iw,int ih,int ow,int oh,String bypass,boolean directInput,boolean nativeRcas) {
    inputWidth=iw;inputHeight=ih;outputWidth=ow;outputHeight=oh;this.bypass=bypass;
    this.directInput=directInput;this.nativeRcas=nativeRcas;
    workingBytes=bypass!=null?0:4L*((directInput?0:(long)iw*ih)+(nativeRcas?0:(long)ow*oh));
  }
  public boolean active() { return bypass==null; }
  public int passes() { return (nativeRcas?1:2)+(directInput?0:1); }
  public static Fsr1Plan create(int iw,int ih,int ow,int oh,int maxTexture,int maxRenderbuffer,int precision) {
    return create(iw,ih,ow,oh,maxTexture,maxRenderbuffer,precision,false,false);
  }
  public static Fsr1Plan create(int iw,int ih,int ow,int oh,int maxTexture,int maxRenderbuffer,int precision,
                               boolean directInput,boolean allowNativeRcas) {
    boolean nativeRcas=allowNativeRcas && iw==ow && ih==oh;
    String reason=null;
    if(iw<=0 || ih<=0 || ow<=0 || oh<=0 || iw>16384 || ih>16384) reason="dimensões inválidas";
    else if(precision<23) reason="GPU sem precisão highp de 23 bits";
    else if(ow>maxTexture || oh>maxTexture || ow>maxRenderbuffer || oh>maxRenderbuffer) reason="limite de textura da GPU";
    else if((long)ow*oh>MAX_PIXELS) reason="saída acima do limite 4K";
    else if(ow<=iw && oh<=ih && !nativeRcas) reason=NO_UPSCALING;
    int inputW=Math.min(Math.max(0,iw),Math.max(0,ow)),inputH=Math.min(Math.max(0,ih),Math.max(0,oh));
    Fsr1Plan p=new Fsr1Plan(inputW,inputH,ow,oh,reason,directInput,nativeRcas);
    return p.workingBytes>MAX_BYTES?new Fsr1Plan(inputW,inputH,ow,oh,"limite de memória do filtro",directInput,nativeRcas):p;
  }
  public static float rcasStrength(int percent) {
    return percent<=0?0f:(float)Math.pow(2.0,-2.0*(1.0-Math.min(100,percent)/100.0));
  }
}
