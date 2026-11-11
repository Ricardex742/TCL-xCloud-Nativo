package org.webrtc;

import android.content.Context;
import android.opengl.GLES20;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Locale;

/** Spatial EASU + RCAS. All GL work stays on EglRenderer's thread, without readback/history. */
public final class Fsr1Drawer implements FsrUpscaler {
  enum Input { OES, RGB, YUV }
  interface Backend {
    void begin();
    int[] capabilities(); // max texture, max renderbuffer, highp mantissa bits
    void prepare(Fsr1Plan plan);
    void copy(Input kind,int texture,int[] textures,float[] matrix,int fw,int fh,Fsr1Plan plan);
    void easu(Fsr1Plan plan);
    void easuDirect(Input kind,int texture,float[] matrix,Fsr1Plan plan);
    void rcasDirect(Input kind,int texture,float[] matrix,Fsr1Plan plan,int x,int y,float strength,float saturation);
    void discardDirect();
    default void colors(int brightness,int contrast) {}
    void rcas(Fsr1Plan plan,int x,int y,float strength,float saturation);
    void end();
    void release();
  }
  private final Backend backend;
  private volatile boolean broken,closed;
  private volatile boolean directRejected;
  private int sourceWidth,sourceHeight,lastWidth=-1,lastHeight=-1,lastOutputWidth,lastOutputHeight;
  private boolean lastDirect,lastNative;
  private int[] caps;
  private volatile int sharpness=50,saturation=100,brightness=0,contrast=100;
  private volatile Fsr1Plan plan;
  private volatile String status="aguardando vídeo";
  private volatile long frames,totalNs,maxNs;
  public Fsr1Drawer(Context context) { this(new GlesBackend(context.getApplicationContext())); }
  Fsr1Drawer(Backend backend) { this.backend=backend; }
  @Override public void configure(int width,int height,int sharpness,int saturation) {
    sourceWidth=width;sourceHeight=height;
    this.sharpness=Math.max(0,Math.min(100,sharpness));this.saturation=Math.max(0,Math.min(200,saturation));
  }
  @Override public void setColorAdjustments(int brightness,int contrast) {
    this.brightness=Math.max(-50,Math.min(50,brightness));this.contrast=Math.max(50,Math.min(150,contrast));
  }
  @Override public boolean drawOes(int t,float[] m,int fw,int fh,int x,int y,int w,int h) {
    return draw(Input.OES,t,null,m,fw,fh,x,y,w,h);
  }
  @Override public boolean drawRgb(int t,float[] m,int fw,int fh,int x,int y,int w,int h) {
    return draw(Input.RGB,t,null,m,fw,fh,x,y,w,h);
  }
  @Override public boolean drawYuv(int[] t,float[] m,int fw,int fh,int x,int y,int w,int h) {
    return draw(Input.YUV,0,t,m,fw,fh,x,y,w,h);
  }
  private boolean draw(Input kind,int texture,int[] textures,float[] matrix,int fw,int fh,int x,int y,int w,int h) {
    if(closed || broken) return false;
    boolean direct=kind!=Input.YUV && !directRejected;
    boolean nativeRcas=sharpness>0 && sourceWidth==w && sourceHeight==h;
    boolean changed=plan==null || lastWidth!=sourceWidth || lastHeight!=sourceHeight || lastOutputWidth!=w || lastOutputHeight!=h || lastDirect!=direct || lastNative!=nativeRcas;
    if(!changed && !plan.active()) return false;
    boolean begun=false;
    long start=System.nanoTime();
    try {
      backend.begin();begun=true;
      if(caps==null) caps=backend.capabilities();
      if(changed) {
        lastWidth=sourceWidth;lastHeight=sourceHeight;lastOutputWidth=w;lastOutputHeight=h;
        lastDirect=direct;lastNative=nativeRcas;
        plan=Fsr1Plan.create(sourceWidth,sourceHeight,w,h,caps[0],caps[1],caps[2],direct,nativeRcas);
        if(!plan.active()) { status=plan.bypass;backend.release();return false; }
      }
      try {
        render(kind,texture,textures,matrix,fw,fh,x,y);
      } catch(RuntimeException directFailure) {
        if(!plan.directInput) throw directFailure;
        // One bounded compatibility retry: never recompile a rejected direct path every frame.
        directRejected=true;lastDirect=false;
        backend.discardDirect();
        plan=Fsr1Plan.create(sourceWidth,sourceHeight,w,h,caps[0],caps[1],caps[2],false,nativeRcas);
        if(!plan.active()) { status=plan.bypass;backend.release();return false; }
        render(kind,texture,textures,matrix,fw,fh,x,y);
      }
      status=plan.nativeRcas?"ativo RCAS nativo (sem EASU)":"ativo EASU + RCAS (denoise)";
      long elapsed=System.nanoTime()-start;
      totalNs+=elapsed;maxNs=Math.max(maxNs,elapsed);frames++;
      return true;
    } catch(RuntimeException error) {
      broken=true;status="retorno à escala padrão: shader/GPU recusou o filtro";
      try { backend.release(); } catch(RuntimeException ignored) { /* Restore the caller's target below. */ }
      return false;
    } finally { if(begun) backend.end(); }
  }
  private void render(Input kind,int texture,int[] textures,float[] matrix,int fw,int fh,int x,int y) {
    Fsr1Plan p=plan;
    backend.prepare(p);backend.colors(brightness,contrast);
    float strength=Fsr1Plan.rcasStrength(sharpness),sat=saturation/100f;
    if(p.directInput) {
      if(p.nativeRcas) backend.rcasDirect(kind,texture,matrix,p,x,y,strength,sat);
      else { backend.easuDirect(kind,texture,matrix,p);backend.rcas(p,x,y,strength,sat); }
    } else {
      backend.copy(kind,texture,textures,matrix,fw,fh,p);
      if(!p.nativeRcas) backend.easu(p);
      backend.rcas(p,x,y,strength,sat);
    }
  }
  @Override public boolean isBypassed() {
    Fsr1Plan p=plan;
    return !broken && !closed && p!=null && Fsr1Plan.NO_UPSCALING.equals(p.bypass);
  }
  @Override public String failureReason() {
    if(broken) return status;
    Fsr1Plan p=plan;
    return p!=null && !p.active() && !Fsr1Plan.NO_UPSCALING.equals(p.bypass)?p.bypass:null;
  }
  @Override public String diagnostics() {
    Fsr1Plan p=plan;
    String dimensions=!broken && !closed && p!=null && p.active()?" | "+p.passes()+" passagem(ns) • "+
        (p.directInput?"textura direta":"preparação RGB")+" | "+p.inputWidth+"x"+p.inputHeight+" → "+p.outputWidth+"x"+p.outputHeight+
        String.format(Locale.US," | texturas estimadas %.1f MiB",p.workingBytes/1048576.0):"";
    long count=frames;
    String timing=count==0?"":" | submissão CPU/GL média/pico "+String.format(Locale.US,"%.2f/%.2f ms",totalNs/1e6/count,maxNs/1e6)+" (não mede GPU/tela)";
    return "AMD FSR 1.0.2: "+status+dimensions+" | RCAS "+sharpness+"%"+
        (directRejected?" | caminho direto recusado; compatibilidade nesta sessão":"")+timing;
  }
  @Override public void release() { if(closed) return;closed=true;backend.release(); }

  private static final class Sources {
    final String vertex,easu,rcas;
    Sources(Context context) { vertex=read(context,"fullscreen.vert");easu=read(context,"easu.frag");rcas=read(context,"rcas.frag"); }
    private static String read(Context context,String file) {
      try(InputStream in=context.getAssets().open("fsr1/"+file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
        byte[] buffer=new byte[4096];int n;
        while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
        return out.toString("UTF-8");
      } catch(IOException error) { throw new IllegalStateException("Shaders FSR indisponíveis",error); }
    }
  }
  private static Sources cachedSources;
  private static synchronized Sources sources(Context context) {
    if(cachedSources==null) cachedSources=new Sources(context);
    return cachedSources;
  }
  static String copyVertexShader() {
    return "varying highp vec2 tc;\nattribute vec4 in_pos;\nattribute vec4 in_tc;\nuniform mat4 tex_mat;\n"+
        "void main(){gl_Position=in_pos;tc=(tex_mat*in_tc).xy;}\n";
  }
  static String copyFragmentShader(GlGenericDrawer.ShaderType type) {
    return GlGenericDrawer.createFragmentShaderString("void main(){gl_FragColor=sample(tc);}\n",type)
        .replace("precision mediump float;","precision highp float;")
        .replace("varying vec2 tc;","varying highp vec2 tc;");
  }
  /** Retain WebRTC's exact samplers/color transform, with full precision in the input copy too. */
  private static final class CopyDrawer extends GlGenericDrawer {
    CopyDrawer() {
      super(copyVertexShader(),"void main(){gl_FragColor=sample(tc);}\n",new ShaderCallbacks() {
        @Override public void onNewShader(GlShader shader) {}
        @Override public void onPrepareShader(GlShader shader,float[] m,int fw,int fh,int vw,int vh) {}
      });
    }
    @Override GlShader createShader(ShaderType type) { return new GlShader(copyVertexShader(),copyFragmentShader(type)); }
  }
  static String directShader(String source,Input kind) {
    if(kind==null) return source;
    if(kind==Input.YUV) throw new IllegalArgumentException("YUV requires RGB preparation");
    return "#define FSR_DIRECT_INPUT 1\n"+(kind==Input.OES?"#define FSR_SOURCE_OES 1\n":"")+source;
  }
  /** Pixel-center transform matches the old RGB preparation, including rotation/crop/mirroring. */
  static void sourceTransform(float[] m,int width,int height,float[] out) {
    out[2]=m[0]/width;out[3]=m[1]/width;out[4]=m[4]/height;out[5]=m[5]/height;
    out[0]=m[12]+out[2]*.5f+out[4]*.5f;out[1]=m[13]+out[3]*.5f+out[5]*.5f;
  }
  private static final class Pass {
    final GlShader shader;
    final int position,texture,input,output,origin,strength,saturation,brightness,contrast;
    final int sourceOrigin,sourceX,sourceY;
    Pass(Sources source,boolean rcas) { this(source,rcas,null); }
    Pass(Sources source,boolean rcas,Input direct) {
      GlShader s=new GlShader(source.vertex,directShader(rcas?source.rcas:source.easu,direct));
      try {
        position=s.getAttribLocation("in_pos");texture=s.getUniformLocation("source_tex");input=s.getUniformLocation("input_size");
        output=rcas?-1:s.getUniformLocation("output_size");origin=rcas?s.getUniformLocation("viewport_origin"):-1;
        strength=rcas?s.getUniformLocation("rcas_strength"):-1;saturation=rcas?s.getUniformLocation("saturation"):-1;
        brightness=rcas?s.getUniformLocation("brightness"):-1;contrast=rcas?s.getUniformLocation("contrast_adjust"):-1;
        sourceOrigin=direct==null?-1:s.getUniformLocation("source_origin");
        sourceX=direct==null?-1:s.getUniformLocation("source_step_x");sourceY=direct==null?-1:s.getUniformLocation("source_step_y");
        shader=s;
      } catch(RuntimeException error) { s.release();throw error; }
    }
  }
  private static final class GlesBackend implements Backend {
    private float brightness,contrast;
    @Override public void colors(int brightness,int contrast) { this.brightness=brightness/100f;this.contrast=contrast/100f-1f; }
    private static final FloatBuffer QUAD=GlUtil.createFloatBuffer(new float[]{-1,-1,1,-1,-1,1,1,1});
    private final Context context;
    private final int[] framebuffer=new int[1],viewport=new int[4],activeTexture=new int[1];
    private boolean scissor,blend,boundOes;
    private final float[] transform=new float[6];
    private final Pass[] directEasu=new Pass[2],directRcas=new Pass[2];
    private CopyDrawer copy;
    private GlTextureFrameBuffer input,upscaled;
    private Pass easu,rcas;
    GlesBackend(Context context) { this.context=context; }
    @Override public void begin() {
      GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,framebuffer,0);
      GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
      GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE,activeTexture,0);
      boundOes=false;
      scissor=GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);blend=GLES20.glIsEnabled(GLES20.GL_BLEND);
    }
    @Override public int[] capabilities() {
      int[] texture=new int[1],renderbuffer=new int[1],range=new int[2],precision=new int[1];
      GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,texture,0);
      GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE,renderbuffer,0);
      GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER,GLES20.GL_HIGH_FLOAT,range,0,precision,0);
      GlUtil.checkNoGLES2Error("FSR capabilities");
      return new int[]{texture[0],renderbuffer[0],precision[0]};
    }
    @Override public void prepare(Fsr1Plan p) {
      // Also reset for the same-frame compatibility retry after a failed final pass.
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND);
      Sources s=sources(context);
      if(!p.directInput) {
        if(copy==null) copy=new CopyDrawer();
        if(input==null) input=new GlTextureFrameBuffer(GLES20.GL_RGBA);
        resize(input,p.inputWidth,p.inputHeight);
        if(!p.nativeRcas && easu==null) easu=new Pass(s,false);
      } else if(input!=null) { input.release();input=null; }
      if(!p.directInput || !p.nativeRcas) { if(rcas==null) rcas=new Pass(s,true); }
      if(!p.nativeRcas) {
        if(upscaled==null) upscaled=new GlTextureFrameBuffer(GLES20.GL_RGBA);
        resize(upscaled,p.outputWidth,p.outputHeight);
      } else if(upscaled!=null) { upscaled.release();upscaled=null; }
    }
    private static void resize(GlTextureFrameBuffer target,int w,int h) {
      if(target.getWidth()==w && target.getHeight()==h) return;
      target.setSize(w,h);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,target.getTextureId());
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0);
      GlUtil.checkNoGLES2Error("FSR texture");
    }
    @Override public void copy(Input kind,int t,int[] textures,float[] m,int fw,int fh,Fsr1Plan p) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,input.getFrameBufferId());
      if(kind==Input.OES) copy.drawOes(t,m,fw,fh,0,0,p.inputWidth,p.inputHeight);
      else if(kind==Input.RGB) copy.drawRgb(t,m,fw,fh,0,0,p.inputWidth,p.inputHeight);
      else copy.drawYuv(textures,m,fw,fh,0,0,p.inputWidth,p.inputHeight);
      GlUtil.checkNoGLES2Error("FSR RGB preparation");
    }
    private static void start(Pass pass,int texture,int iw,int ih,int x,int y,int w,int h) {
      start(pass,texture,GLES20.GL_TEXTURE_2D,iw,ih,x,y,w,h);
    }
    private static void start(Pass pass,int texture,int target,int iw,int ih,int x,int y,int w,int h) {
      pass.shader.useProgram();QUAD.position(0);
      GLES20.glEnableVertexAttribArray(pass.position);
      GLES20.glVertexAttribPointer(pass.position,2,GLES20.GL_FLOAT,false,0,QUAD);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(target,texture);
      GLES20.glUniform1i(pass.texture,0);GLES20.glUniform2f(pass.input,iw,ih);GLES20.glViewport(x,y,w,h);
    }
    @Override public void easu(Fsr1Plan p) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,upscaled.getFrameBufferId());
      start(easu,input.getTextureId(),p.inputWidth,p.inputHeight,0,0,p.outputWidth,p.outputHeight);
      GLES20.glUniform2f(easu.output,p.outputWidth,p.outputHeight);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("FSR EASU");
    }
    private Pass directPass(Input kind,boolean rcas) {
      if(kind==Input.YUV) throw new IllegalArgumentException("YUV requires RGB preparation");
      Pass[] cache=rcas?directRcas:directEasu;
      if(cache[kind.ordinal()]==null) cache[kind.ordinal()]=new Pass(sources(context),rcas,kind);
      return cache[kind.ordinal()];
    }
    private void startDirect(Pass pass,Input kind,int texture,float[] m,Fsr1Plan p,int x,int y) {
      int target=kind==Input.OES?android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES:GLES20.GL_TEXTURE_2D;
      if(kind==Input.OES) boundOes=true;
      start(pass,texture,target,p.inputWidth,p.inputHeight,x,y,p.outputWidth,p.outputHeight);
      sourceTransform(m,p.inputWidth,p.inputHeight,transform);
      GLES20.glUniform2f(pass.sourceOrigin,transform[0],transform[1]);
      GLES20.glUniform2f(pass.sourceX,transform[2],transform[3]);GLES20.glUniform2f(pass.sourceY,transform[4],transform[5]);
    }
    @Override public void easuDirect(Input kind,int texture,float[] m,Fsr1Plan p) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,upscaled.getFrameBufferId());
      Pass pass=directPass(kind,false);startDirect(pass,kind,texture,m,p,0,0);
      GLES20.glUniform2f(pass.output,p.outputWidth,p.outputHeight);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("FSR direct EASU");
    }
    @Override public void rcasDirect(Input kind,int texture,float[] m,Fsr1Plan p,int x,int y,float strength,float saturation) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
      if(scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
      Pass pass=directPass(kind,true);startDirect(pass,kind,texture,m,p,x,y);setRcas(pass,x,y,strength,saturation);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("FSR direct RCAS");
    }
    private void setRcas(Pass pass,int x,int y,float strength,float saturation) {
      GLES20.glUniform2f(pass.origin,x,y);GLES20.glUniform1f(pass.strength,strength);GLES20.glUniform1f(pass.saturation,saturation);
      GLES20.glUniform1f(pass.brightness,brightness);GLES20.glUniform1f(pass.contrast,contrast);
    }
    @Override public void discardDirect() {
      RuntimeException failure=null;
      for(Pass[] cache:new Pass[][]{directEasu,directRcas}) for(int i=0;i<cache.length;i++) {
        Pass pass=cache[i];cache[i]=null;
        try { if(pass!=null) pass.shader.release(); } catch(RuntimeException error) { if(failure==null) failure=error; }
      }
      if(failure!=null) throw failure;
    }
    @Override public void rcas(Fsr1Plan p,int x,int y,float strength,float saturation) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
      if(scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
      start(rcas,(p.nativeRcas?input:upscaled).getTextureId(),p.outputWidth,p.outputHeight,x,y,p.outputWidth,p.outputHeight);
      setRcas(rcas,x,y,strength,saturation);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("FSR RCAS");
    }
    @Override public void end() {
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0);
      if(boundOes) GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,0);
      GLES20.glActiveTexture(activeTexture[0]);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
      GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
      if(scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
      if(blend) GLES20.glEnable(GLES20.GL_BLEND);else GLES20.glDisable(GLES20.GL_BLEND);
    }
    @Override public void release() {
      RuntimeException failure=null;
      Runnable[] cleanup={()->{if(input!=null) input.release();},()->{if(upscaled!=null) upscaled.release();},
          ()->{if(easu!=null) easu.shader.release();},()->{if(rcas!=null) rcas.shader.release();},()->{if(copy!=null) copy.release();},this::discardDirect};
      for(Runnable action:cleanup) try { action.run(); } catch(RuntimeException error) { if(failure==null) failure=error; }
      input=null;upscaled=null;easu=null;rcas=null;copy=null;
      if(failure!=null) throw failure;
    }
  }
}
