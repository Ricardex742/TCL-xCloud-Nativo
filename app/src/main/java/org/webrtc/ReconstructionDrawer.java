package org.webrtc;

import android.content.Context;
import android.opengl.GLES20;
import java.util.HashMap;
import java.util.Map;

/** Source texture → display in one pass. Variants have a fixed, bounded sample footprint. */
public final class ReconstructionDrawer extends GlGenericDrawer {
  private final String source;
  private final Parameters parameters;
  public ReconstructionDrawer(Context context,int level) { this(context,Math.max(1,Math.min(3,level)),new Parameters()); }
  private ReconstructionDrawer(Context context,int level,Parameters p) {
    this("#define RECON_LEVEL "+level+"\n"+TreatmentShaderDrawer.asset(context,"reconstruction.glsl"),p);
  }
  private ReconstructionDrawer(String source,Parameters p) { super(Fsr1Drawer.copyVertexShader(),source,p);this.source=source;parameters=p; }
  @Override GlShader createShader(ShaderType type) {
    return new GlShader(Fsr1Drawer.copyVertexShader(),GlGenericDrawer.createFragmentShaderString(source,type)
        .replace("precision mediump float;","precision highp float;").replace("varying vec2 tc;","varying highp vec2 tc;"));
  }
  public void configure(int strength,int saturation,int brightness,int contrast,boolean dither) {
    parameters.strength=Math.max(0,Math.min(100,strength))/100f;parameters.saturation=saturation/100f;
    parameters.brightness=brightness/100f;parameters.contrast=contrast/100f-1;parameters.dither=dither;
  }
  @Override public void drawOes(int t,float[] m,int fw,int fh,int x,int y,int w,int h) { parameters.x=x;parameters.y=y;super.drawOes(t,m,fw,fh,x,y,w,h); }
  @Override public void drawRgb(int t,float[] m,int fw,int fh,int x,int y,int w,int h) { parameters.x=x;parameters.y=y;super.drawRgb(t,m,fw,fh,x,y,w,h); }
  @Override public void drawYuv(int[] t,float[] m,int fw,int fh,int x,int y,int w,int h) { parameters.x=x;parameters.y=y;super.drawYuv(t,m,fw,fh,x,y,w,h); }
  private static final class Parameters implements ShaderCallbacks {
    final Map<String,Integer> uniforms=new HashMap<>();GlShader shader;
    int x,y;float strength=.5f,saturation=1,brightness,contrast;boolean dither;
    int u(String key) { Integer cached=uniforms.get(key);if(cached!=null)return cached;int value;
      try { value=shader.getUniformLocation(key); } catch(RuntimeException optimizedOut) { value=-1; }
      uniforms.put(key,value);return value; }
    @Override public void onNewShader(GlShader shader) { this.shader=shader;uniforms.clear(); }
    @Override public void onPrepareShader(GlShader shader,float[] m,int fw,int fh,int vw,int vh) {
      if(this.shader!=shader) { this.shader=shader;uniforms.clear(); }
      GLES20.glUniform2f(u("step_x"),m[0]/fw,m[1]/fw);GLES20.glUniform2f(u("step_y"),m[4]/fh,m[5]/fh);
      GLES20.glUniform2f(u("source_size"),fw,fh);GLES20.glUniform2f(u("output_size"),vw,vh);GLES20.glUniform2f(u("viewport_origin"),x,y);
      // Bound every tap to the transformed decoder crop, not to padded texture pixels.
      float minX=m[12]+Math.min(0,m[0])+Math.min(0,m[4]),maxX=m[12]+Math.max(0,m[0])+Math.max(0,m[4]);
      float minY=m[13]+Math.min(0,m[1])+Math.min(0,m[5]),maxY=m[13]+Math.max(0,m[1])+Math.max(0,m[5]);
      float halfX=.5f*(Math.abs(m[0])/fw+Math.abs(m[4])/fh),halfY=.5f*(Math.abs(m[1])/fw+Math.abs(m[5])/fh);
      GLES20.glUniform2f(u("bounds_min"),minX+halfX,minY+halfY);GLES20.glUniform2f(u("bounds_max"),maxX-halfX,maxY-halfY);
      GLES20.glUniform1f(u("reconstruction_strength"),strength);GLES20.glUniform1f(u("saturation"),saturation);
      GLES20.glUniform1f(u("brightness"),brightness);GLES20.glUniform1f(u("contrast_adjust"),contrast);GLES20.glUniform1i(u("finish_dither"),dither?1:0);
    }
  }
}
