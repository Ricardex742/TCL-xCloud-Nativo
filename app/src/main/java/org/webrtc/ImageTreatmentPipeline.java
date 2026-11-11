package org.webrtc;

import android.content.Context;
import android.opengl.GLES20;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Optional source-resolution restoration. All objects/GL work belong to EglRenderer's thread. */
public final class ImageTreatmentPipeline {
  public enum Input { OES,RGB,YUV }
  private static final long MAX_BYTES=64L*1024*1024,NEURAL_PIXELS=1920L*1080;
  private static final float[] IDENTITY=new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
  private static final String VERTEX="attribute vec2 in_pos;varying vec2 tc;void main(){gl_Position=vec4(in_pos,0.,1.);tc=in_pos*.5+.5;}";
  private static final FloatBuffer QUAD=GlUtil.createFloatBuffer(new float[]{-1,-1,1,-1,-1,1,1,1});
  private final Context context;
  private final int[] framebuffer=new int[1],viewport=new int[4],activeTexture=new int[1];
  private boolean scissor,blend,closed,broken,historyValid,neuralRejected;
  private TreatmentOptions options=TreatmentOptions.OFF;
  private TreatmentOptions finishingOptions=TreatmentOptions.OFF;
  private int sharpness,fsrSharpness=50,saturation=100,brightness,contrast=100,lastW,lastH;
  private long lastFrameNs;
  private final float[] lastTransform=new float[16];
  private boolean transformKnown;
  private GlTextureFrameBuffer a,b,history;
  private HalfTarget featureA,featureB;
  private GlRectDrawer copy;
  private TreatmentShaderDrawer spatial,sgsr;
  private Pass featurePassA,featurePassB,neuralApply,temporal;
  private volatile String failure,neuralStatus="off";
  private volatile int passes;
  private volatile boolean usedTemporal,usedNeural;
  private volatile boolean usedScaling;
  private volatile long frames,totalNs,maxNs,workingBytes;

  public ImageTreatmentPipeline(Context context) { this.context=context.getApplicationContext(); }
  public void configure(TreatmentOptions requested,int sharpness,int fsrSharpness,int saturation,int brightness,int contrast) {
    if(options!=requested) {
      historyValid=false;finishingOptions=new TreatmentOptions(requested.sharpening,0,0,0,0,0,0);
    }
    options=requested;this.sharpness=sharpness;this.fsrSharpness=fsrSharpness;
    this.saturation=saturation;this.brightness=brightness;this.contrast=contrast;
  }
  private TreatmentShaderDrawer spatial() {
    if(spatial==null) spatial=new TreatmentShaderDrawer(context,false);
    return spatial;
  }
  private static void input(GlRectDrawer drawer,Input kind,int texture,int[] textures,float[] matrix,
      int fw,int fh,int x,int y,int w,int h) {
    if(kind==Input.OES) drawer.drawOes(texture,matrix,fw,fh,x,y,w,h);
    else if(kind==Input.RGB) drawer.drawRgb(texture,matrix,fw,fh,x,y,w,h);
    else drawer.drawYuv(textures,matrix,fw,fh,x,y,w,h);
  }
  private static void input(TreatmentShaderDrawer drawer,Input kind,int texture,int[] textures,float[] matrix,
      int fw,int fh,int x,int y,int w,int h) {
    if(kind==Input.OES) drawer.drawOes(texture,matrix,fw,fh,x,y,w,h);
    else if(kind==Input.RGB) drawer.drawRgb(texture,matrix,fw,fh,x,y,w,h);
    else drawer.drawYuv(textures,matrix,fw,fh,x,y,w,h);
  }
  private GlTextureFrameBuffer target(boolean second,int w,int h) {
    GlTextureFrameBuffer out=second?b:a;
    if(out==null) { out=new GlTextureFrameBuffer(GLES20.GL_RGBA);if(second)b=out;else a=out; }
    out.setSize(w,h);return out;
  }
  private static void bind(GlTextureFrameBuffer target) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target.getFrameBufferId()); }
  private void original() {
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
    if(scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
  }
  public boolean draw(Input kind,int texture,int[] textures,float[] matrix,int fw,int fh,int iw,int ih,
      int x,int y,int width,int height,FsrUpscaler fsr,boolean useSgsr) {
    if(closed || broken || iw<=0 || ih<=0) return false;
    long started=System.nanoTime();
    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,framebuffer,0);
    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
    GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE,activeTexture,0);
    scissor=GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);blend=GLES20.glIsEnabled(GLES20.GL_BLEND);
    try {
      passes=0;usedTemporal=false;usedNeural=false;usedScaling=false;
      long pixels=(long)iw*ih;
      if(pixels>3840L*2160 || pixels*12>MAX_BYTES) throw new IllegalStateException("source exceeds treatment memory limit");
      boolean changed=iw!=lastW || ih!=lastH;
      if(changed || started-lastFrameNs>200_000_000L) historyValid=false;
      if(!transformKnown || !java.util.Arrays.equals(matrix,lastTransform)) {
        historyValid=false;System.arraycopy(matrix,0,lastTransform,0,16);transformKnown=true;
      }
      lastW=iw;lastH=ih;lastFrameNs=started;
      boolean wantsHistory=options.temporal>0, wantsNeural=options.neural>0 && neuralAvailable(iw,ih);
      if(!wantsHistory && history!=null) { history.release();history=null;historyValid=false; }
      if(!wantsNeural) releaseFeatures();
      if(!wantsHistory && !wantsNeural) {
        if(b!=null) { b.release();b=null; }
        if((!options.spatial() || fsr==null && !useSgsr) && a!=null) { a.release();a=null; }
      }
      if(!wantsHistory && !wantsNeural && !options.spatial() && sharpness==0 && fsr==null && !useSgsr) {
        workingBytes=0;return false;
      }
      if(!wantsHistory && !wantsNeural && fsr==null && !useSgsr) {
        // Common 1080p case: selective cleanup + colors + sharpening in the existing input pass.
        original();spatial().configure(options,sharpness,saturation,brightness,contrast,true);
        input(spatial(),kind,texture,textures,matrix,fw,fh,x,y,width,height);passes++;
      } else {
        Input finalKind=kind;int finalTexture=texture;int[] finalTextures=textures;
        float[] finalMatrix=matrix;int finalW=fw,finalH=fh;
        if(wantsHistory || wantsNeural || options.spatial()) {
          GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND);
          GlTextureFrameBuffer current=target(false,iw,ih);
          if(copy==null) copy=new GlRectDrawer();
          bind(current);
          if(!wantsHistory && !wantsNeural) {
            spatial().configure(options,0,100,0,100,false);
            input(spatial(),kind,texture,textures,matrix,fw,fh,0,0,iw,ih);
          } else input(copy,kind,texture,textures,matrix,fw,fh,0,0,iw,ih);
          passes++;
          if(wantsNeural) {
            try {
              if(featurePassA==null) featurePassA=new Pass(TreatmentShaderDrawer.asset(context,"neural-0.frag"));
              if(featurePassB==null) featurePassB=new Pass(TreatmentShaderDrawer.asset(context,"neural-1.frag"));
              if(neuralApply==null) neuralApply=new Pass(TreatmentShaderDrawer.asset(context,"neural-apply.frag"));
              featurePassA.draw(featureA.framebuffer,iw,ih,current.getTextureId(),0,0,0);
              featurePassB.draw(featureB.framebuffer,iw,ih,current.getTextureId(),0,0,0);
              GlTextureFrameBuffer out=target(true,iw,ih);
              neuralApply.draw(out.getFrameBufferId(),iw,ih,current.getTextureId(),featureA.texture,featureB.texture,options.neural/100f);
              current=out;passes+=3;usedNeural=true;neuralStatus="active (3 GLES2 passes, half-float features)";
            } catch(RuntimeException rejected) {
              neuralRejected=true;neuralStatus="unavailable: "+rejected.getClass().getSimpleName();releaseFeatures();clearErrors();
            }
          }
          if(options.spatial() && (wantsHistory || wantsNeural)) {
            GlTextureFrameBuffer out=target(current==a,iw,ih);bind(out);
            spatial().configure(options,0,100,0,100,false);
            spatial().drawRgb(current.getTextureId(),IDENTITY,iw,ih,0,0,iw,ih);current=out;passes++;
          }
          if(wantsHistory) {
            if(history==null) history=new GlTextureFrameBuffer(GLES20.GL_RGBA);
            history.setSize(iw,ih);
            GlTextureFrameBuffer next=current;
            if(historyValid) {
              if(temporal==null) temporal=new Pass(TreatmentShaderDrawer.asset(context,"temporal.frag"));
              next=target(current==a,iw,ih);
              temporal.draw(next.getFrameBufferId(),iw,ih,current.getTextureId(),history.getTextureId(),0,options.temporal/100f);
              passes++;usedTemporal=true;
            }
            bind(history);copy.drawRgb(current.getTextureId(),IDENTITY,iw,ih,0,0,iw,ih);passes++;historyValid=true;current=next;
          }
          finalKind=Input.RGB;finalTexture=current.getTextureId();finalTextures=null;finalMatrix=IDENTITY;finalW=iw;finalH=ih;
        }
        original();
        if(useSgsr) {
          if(sgsr==null) sgsr=new TreatmentShaderDrawer(context,true);
          sgsr.configure(options,fsrSharpness,saturation,brightness,contrast,true);
          input(sgsr,finalKind,finalTexture,finalTextures,finalMatrix,finalW,finalH,x,y,width,height);passes++;usedScaling=true;
        } else if(fsr!=null && drawFsr(fsr,finalKind,finalTexture,finalTextures,finalMatrix,finalW,finalH,x,y,width,height)) {
          passes+=fsr.lastPassCount();usedScaling=true;
        } else {
          spatial().configure(finishingOptions,sharpness,saturation,brightness,contrast,true);
          spatial().setFinalDither(options.deband>0);
          input(spatial(),finalKind,finalTexture,finalTextures,finalMatrix,finalW,finalH,x,y,width,height);passes++;
        }
      }
      GlUtil.checkNoGLES2Error("image treatment");
      long elapsed=System.nanoTime()-started;totalNs+=elapsed;maxNs=Math.max(maxNs,elapsed);frames++;
      workingBytes=pixels*4*((a!=null?1:0)+(b!=null?1:0)+(history!=null?1:0))+(featureA!=null?pixels*16:0);
      return true;
    } catch(RuntimeException error) {
      broken=true;failure="image treatment rejected: "+error.getClass().getSimpleName();clearErrors();return false;
    } finally {
      for(int unit=0;unit<3;unit++) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0+unit);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0); }
      if(kind==Input.OES) { GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0); }
      GLES20.glActiveTexture(activeTexture[0]);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
      GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
      if(scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
      if(blend) GLES20.glEnable(GLES20.GL_BLEND);else GLES20.glDisable(GLES20.GL_BLEND);
    }
  }
  private static boolean drawFsr(FsrUpscaler f,Input kind,int texture,int[] textures,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    if(kind==Input.OES) return f.drawOes(texture,matrix,fw,fh,x,y,w,h);
    if(kind==Input.RGB) return f.drawRgb(texture,matrix,fw,fh,x,y,w,h);
    return f.drawYuv(textures,matrix,fw,fh,x,y,w,h);
  }
  private boolean neuralAvailable(int w,int h) {
    if(neuralRejected) return false;
    if((long)w*h>NEURAL_PIXELS || (long)w*h*28>MAX_BYTES) { neuralStatus="source/memory limit (up to 1080p)";return false; }
    if(featureA!=null && featureB!=null && featureA.width==w && featureA.height==h && featureB.width==w && featureB.height==h) return true;
    try {
      int[] range=new int[2],precision=new int[1];
      GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER,GLES20.GL_HIGH_FLOAT,range,0,precision,0);
      if(precision[0]<23) { neuralRejected=true;neuralStatus="full-precision fragment math unavailable";return false; }
      String ext=GLES20.glGetString(GLES20.GL_EXTENSIONS);
      String version=GLES20.glGetString(GLES20.GL_VERSION);
      boolean core=version!=null && version.matches(".*OpenGL ES [3-9].*");
      boolean oes=ext!=null && ext.contains("GL_OES_texture_half_float");
      if(!oes && !core) {
        neuralRejected=true;neuralStatus="half-float rendering not declared";return false;
      }
      if(featureA==null) featureA=new HalfTarget(!oes);if(featureB==null) featureB=new HalfTarget(!oes);
      featureA.resize(w,h);featureB.resize(w,h);return true;
    } catch(RuntimeException rejected) {
      neuralRejected=true;neuralStatus="half-float framebuffer rejected";releaseFeatures();clearErrors();return false;
    }
  }
  private static void clearErrors() { for(int i=0;i<8 && GLES20.glGetError()!=GLES20.GL_NO_ERROR;i++) {} }
  private void releaseFeatures() {
    if(featureA!=null) featureA.release();if(featureB!=null) featureB.release();featureA=null;featureB=null;
  }
  public String failureReason() { return failure; }
  public boolean didScaleThisFrame() { return usedScaling; }
  public int effectiveTemporal() { return usedTemporal && !broken ? options.temporal : 0; }
  public int effectiveNeural() { return usedNeural && !broken ? options.neural : 0; }
  public String diagnostics() {
    return String.format(Locale.US,"Treatment: spatial=%s | sharpen=%d | temporal=%s | neural=%s | passes=%d | storage=%.1f MiB | CPU/GL submission=%.2f/%.2f ms (not GPU/display)",
        options.spatial(),options.sharpening,usedTemporal,options.neural>0?neuralStatus:"off",passes,
        workingBytes/1048576.,frames==0?0:totalNs/1e6/frames,maxNs/1e6)+(failure==null?"":" | "+failure);
  }
  public void release() {
    if(closed)return;closed=true;
    if(a!=null)a.release();if(b!=null)b.release();if(history!=null)history.release();releaseFeatures();
    if(copy!=null)copy.release();if(spatial!=null)spatial.release();if(sgsr!=null)sgsr.release();
    for(Pass pass:new Pass[]{featurePassA,featurePassB,neuralApply,temporal}) if(pass!=null)pass.shader.release();
  }
  private static final class Pass {
    final GlShader shader;final int position;final Map<String,Integer> uniforms=new HashMap<>();
    Pass(String source) { shader=new GlShader(VERTEX,source);position=shader.getAttribLocation("in_pos"); }
    int u(String name) { Integer old=uniforms.get(name);if(old!=null)return old;int value;try { value=shader.getUniformLocation(name); } catch(RuntimeException optimizedOut) { value=-1; } uniforms.put(name,value);return value; }
    void draw(int target,int w,int h,int source,int second,int third,float strength) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,target);GLES20.glViewport(0,0,w,h);shader.useProgram();
      GLES20.glUniform2f(u("frame_size"),w,h);GLES20.glUniform1f(u("strength"),strength);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,source);GLES20.glUniform1i(u("source"),0);
      if(second!=0) { GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,second);GLES20.glUniform1i(u("features_a"),1);GLES20.glUniform1i(u("history_tex"),1); }
      if(third!=0) { GLES20.glActiveTexture(GLES20.GL_TEXTURE2);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,third);GLES20.glUniform1i(u("features_b"),2); }
      QUAD.position(0);GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,QUAD);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("treatment pass");
    }
  }
  private static final class HalfTarget {
    int texture,framebuffer,width,height;
    final boolean core;
    HalfTarget(boolean core) {
      this.core=core;
      int[] id=new int[1];GLES20.glGenTextures(1,id,0);texture=id[0];GLES20.glGenFramebuffers(1,id,0);framebuffer=id[0];
    }
    void resize(int w,int h) {
      if(width==w && height==h)return;
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
      GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,core?0x881A:GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,core?0x140B:0x8D61,null);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
      GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture,0);
      if(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)!=GLES20.GL_FRAMEBUFFER_COMPLETE)
        throw new IllegalStateException("Half-float framebuffer unavailable");
      GlUtil.checkNoGLES2Error("neural feature storage");width=w;height=h;
    }
    void release() { GLES20.glDeleteTextures(1,new int[]{texture},0);GLES20.glDeleteFramebuffers(1,new int[]{framebuffer},0); }
  }
}
