package org.webrtc;

import android.content.Context;
import android.opengl.GLES20;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The WebRTC OES/RGB/YUV adapters retain their original sampling/color conversion. */
public final class TreatmentShaderDrawer extends GlGenericDrawer {
  public static String asset(Context context,String name) {
    try(java.io.InputStream stream=context.getAssets().open("enhancement/"+name)) {
      java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
      byte[] buffer=new byte[4096];int n;
      while((n=stream.read(buffer))!=-1) out.write(buffer,0,n);
      return new String(out.toByteArray(),StandardCharsets.UTF_8);
    } catch(IOException error) { throw new IllegalStateException("Image shader unavailable: "+name,error); }
  }
  private final Parameters values;
  private final String code;
  private static String source(Context context,boolean sgsr) {
    return asset(context,sgsr?"sgsr.glsl":"spatial.glsl")
        .replace("/* ADAPTIVE_SHARPEN */",asset(context,"adaptive.glsl"));
  }
  public TreatmentShaderDrawer(Context context,boolean sgsr) { this(context,sgsr,new Parameters(sgsr)); }
  private TreatmentShaderDrawer(Context context,boolean sgsr,Parameters parameters) { this(source(context,sgsr),parameters); }
  private TreatmentShaderDrawer(String source,Parameters parameters) {
    super(Fsr1Drawer.copyVertexShader(),source,parameters);values=parameters;code=source;
  }
  @Override GlShader createShader(ShaderType type) {
    String fragment=GlGenericDrawer.createFragmentShaderString(code,type)
        .replace("precision mediump float;","precision highp float;")
        .replace("varying vec2 tc;","varying highp vec2 tc;");
    return new GlShader(Fsr1Drawer.copyVertexShader(),fragment);
  }
  public void configure(TreatmentOptions options,int sharpness,int saturation,int brightness,int contrast,boolean finish) {
    values.options=options;values.sharpness=sharpness/100f;values.saturation=saturation/100f;
    values.brightness=brightness/100f;values.contrast=contrast/100f-1f;values.finish=finish;
    values.dither=options.deband>0;
  }
  public void setFinalDither(boolean enabled) { values.dither=enabled; }
  @Override public void drawOes(int texture,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    values.x=x;values.y=y;super.drawOes(texture,matrix,fw,fh,x,y,w,h);
  }
  @Override public void drawRgb(int texture,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    values.x=x;values.y=y;super.drawRgb(texture,matrix,fw,fh,x,y,w,h);
  }
  @Override public void drawYuv(int[] textures,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    values.x=x;values.y=y;super.drawYuv(textures,matrix,fw,fh,x,y,w,h);
  }
  private static final class Parameters implements ShaderCallbacks {
    final boolean sgsr;
    TreatmentOptions options=TreatmentOptions.OFF;
    float sharpness,saturation=1,brightness,contrast;
    boolean finish,dither;
    GlShader shader;
    int x,y;
    final java.util.Map<String,Integer> uniforms=new java.util.HashMap<>();
    Parameters(boolean sgsr) { this.sgsr=sgsr; }
    private int u(String name) {
      Integer old=uniforms.get(name);if(old!=null)return old;
      int value;try { value=shader.getUniformLocation(name); } catch(RuntimeException optimizedOut) { value=-1; } uniforms.put(name,value);return value;
    }
    private void f(String name,float v) { GLES20.glUniform1f(u(name),v); }
    private void i(String name,int v) { GLES20.glUniform1i(u(name),v); }
    @Override public void onNewShader(GlShader shader) { this.shader=shader;uniforms.clear(); }
    @Override public void onPrepareShader(GlShader shader,float[] m,int fw,int fh,int vw,int vh) {
      if(this.shader!=shader) { this.shader=shader;uniforms.clear(); }
      GLES20.glUniform2f(u("step_x"),m[0]/fw,m[1]/fw);
      GLES20.glUniform2f(u("step_y"),m[4]/fh,m[5]/fh);
      f("sharpness",sharpness);f("saturation",saturation);f("brightness",brightness);f("contrast_adjust",contrast);
      f("deband",options.deband/100f);
      if(sgsr) {
        GLES20.glUniform2f(u("source_size"),fw,fh);GLES20.glUniform2f(u("output_size"),vw,vh);
        GLES20.glUniform2f(u("viewport_origin"),x,y);
        GLES20.glUniform2f(u("source_origin"),m[12]+.5f*(m[0]/fw+m[4]/fh),m[13]+.5f*(m[1]/fw+m[5]/fh));
      } else {
        f("cleanup",options.cleanup/100f);f("chroma",options.chroma/100f);f("detail",0);
        f("antialias",options.antialias/100f);f("debandTaps",options.deband<50?2:4);
        i("selective",1);i("quantize",1);i("finish_image",finish?1:0);i("finish_dither",dither?1:0);i("sharpen_mode",options.sharpening);
      }
    }
  }
}
