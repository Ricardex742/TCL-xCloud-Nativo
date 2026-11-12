package org.webrtc;

import android.content.Context;
import android.opengl.GLES20;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Specializes disabled filters out of the shader; input sampling and color precision are preserved. */
public final class TreatmentShaderDrawer implements RendererCommon.GlDrawer {
  public static String asset(Context context,String name) {
    try(java.io.InputStream stream=context.getAssets().open("enhancement/"+name)) {
      java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
      byte[] buffer=new byte[4096];int n;
      while((n=stream.read(buffer))!=-1) out.write(buffer,0,n);
      return new String(out.toByteArray(),StandardCharsets.UTF_8);
    } catch(IOException error) { throw new IllegalStateException("Image shader unavailable: "+name,error); }
  }
  private final Parameters values;
  private final String source;
  private final boolean sgsr;
  private GlGenericDrawer drawer;
  private int features=-1;
  public TreatmentShaderDrawer(Context context,boolean sgsr) {
    this.sgsr=sgsr;values=new Parameters(sgsr);
    source=asset(context,sgsr?"sgsr.glsl":"spatial.glsl").replace("/* ADAPTIVE_SHARPEN */",asset(context,"adaptive.glsl"));
  }
  static int featureMask(TreatmentOptions options) {
    return (options.cleanup>0?1:0)|(options.deband>0?2:0)|(options.chroma>0?4:0)|(options.antialias>0?8:0);
  }
  static String specialize(String source,int mask) {
    return "\n#define SPATIAL_CLEANUP "+((mask&1)!=0?1:0)+"\n#define SPATIAL_DEBAND "+((mask&2)!=0?1:0)+
      "\n#define SPATIAL_CHROMA "+((mask&4)!=0?1:0)+"\n#define SPATIAL_ANTIALIAS "+((mask&8)!=0?1:0)+"\n"+source;
  }
  private GlGenericDrawer drawer() {
    int desired=sgsr?0:featureMask(values.options);
    if(drawer==null || desired!=features) {
      if(drawer!=null) drawer.release();
      features=desired;final String code=sgsr?source:specialize(source,desired);
      drawer=new GlGenericDrawer(Fsr1Drawer.copyVertexShader(),code,values) {
        @Override GlShader createShader(ShaderType type) {
          String fragment=GlGenericDrawer.createFragmentShaderString(code,type)
              .replace("precision mediump float;","precision highp float;")
              .replace("varying vec2 tc;","varying highp vec2 tc;");
          return new GlShader(Fsr1Drawer.copyVertexShader(),fragment);
        }
      };
    }
    return drawer;
  }
  public void configure(TreatmentOptions options,int sharpness,int saturation,int brightness,int contrast,boolean finish) {
    values.options=options;values.sharpness=sharpness/100f;values.saturation=saturation/100f;
    values.brightness=brightness/100f;values.contrast=contrast/100f-1f;values.finish=finish;values.dither=options.deband>0;
  }
  public void setFinalDither(boolean enabled) { values.dither=enabled; }
  @Override public void drawOes(int texture,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    values.x=x;values.y=y;drawer().drawOes(texture,matrix,fw,fh,x,y,w,h);
  }
  @Override public void drawRgb(int texture,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    values.x=x;values.y=y;drawer().drawRgb(texture,matrix,fw,fh,x,y,w,h);
  }
  @Override public void drawYuv(int[] textures,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    values.x=x;values.y=y;drawer().drawYuv(textures,matrix,fw,fh,x,y,w,h);
  }
  @Override public void release() { if(drawer!=null) drawer.release();drawer=null;features=-1; }
  private static final class Parameters implements GlGenericDrawer.ShaderCallbacks {
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
