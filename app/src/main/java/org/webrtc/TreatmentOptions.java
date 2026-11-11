package org.webrtc;

/** Immutable GL-thread snapshot; no Android views or preferences are consulted while drawing. */
public final class TreatmentOptions {
  public static final TreatmentOptions OFF=new TreatmentOptions(0,0,0,0,0,0,0);
  public final int sharpening,deband,cleanup,chroma,antialias,temporal,neural;
  public TreatmentOptions(int sharpening,int deband,int cleanup,int chroma,int antialias,int temporal,int neural) {
    this.sharpening=Math.max(0,Math.min(2,sharpening));
    this.deband=bound(deband);this.cleanup=bound(cleanup);this.chroma=bound(chroma);
    this.antialias=bound(antialias);this.temporal=Math.min(50,bound(temporal));this.neural=bound(neural);
  }
  private static int bound(int v) { return Math.max(0,Math.min(100,v)); }
  public boolean spatial() { return deband>0 || cleanup>0 || chroma>0 || antialias>0; }
  public boolean history() { return temporal>0 || neural>0; }
  public boolean active() { return sharpening!=0 || spatial() || history(); }
}
