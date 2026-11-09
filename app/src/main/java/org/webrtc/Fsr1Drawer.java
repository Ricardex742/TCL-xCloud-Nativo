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
    void rcas(Fsr1Plan plan,int x,int y,float strength,float saturation);
    void end();
    void release();
  }
  private final Backend backend;
  private volatile boolean broken,closed;
  private int sourceWidth,sourceHeight,lastWidth=-1,lastHeight=-1,lastOutputWidth,lastOutputHeight;
  private int[] caps;
  private volatile int sharpness=50,saturation=100;
  private volatile Fsr1Plan plan;
  private volatile String status="aguardando ampliação";
  private volatile long frames,totalNs,maxNs;
  public Fsr1Drawer(Context context) { this(new GlesBackend(context.getApplicationContext())); }
  Fsr1Drawer(Backend backend) { this.backend=backend; }
  @Override public void configure(int width,int height,int sharpness,int saturation) {
    sourceWidth=width;sourceHeight=height;
    this.sharpness=Math.max(0,Math.min(100,sharpness));this.saturation=Math.max(0,Math.min(200,saturation));
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
    if(plan!=null && lastWidth==sourceWidth && lastHeight==sourceHeight && lastOutputWidth==w && lastOutputHeight==h && !plan.active()) return false;
    boolean begun=false;
    long start=System.nanoTime();
    try {
      backend.begin();begun=true;
      if(caps==null) caps=backend.capabilities();
      if(plan==null || lastWidth!=sourceWidth || lastHeight!=sourceHeight || lastOutputWidth!=w || lastOutputHeight!=h) {
        lastWidth=sourceWidth;lastHeight=sourceHeight;lastOutputWidth=w;lastOutputHeight=h;
        plan=Fsr1Plan.create(sourceWidth,sourceHeight,w,h,caps[0],caps[1],caps[2]);
        if(!plan.active()) { status=plan.bypass;backend.release();return false; }
        status="ativo EASU + RCAS (denoise)";
      }
      backend.prepare(plan);
      backend.copy(kind,texture,textures,matrix,fw,fh,plan);
      backend.easu(plan);
      backend.rcas(plan,x,y,Fsr1Plan.rcasStrength(sharpness),saturation/100f);
      long elapsed=System.nanoTime()-start;
      totalNs+=elapsed;maxNs=Math.max(maxNs,elapsed);frames++;
      return true;
    } catch(RuntimeException error) {
      broken=true;status="retorno à escala padrão: shader/GPU recusou o filtro";
      try { backend.release(); } catch(RuntimeException ignored) { /* Restore the caller's target below. */ }
      return false;
    } finally { if(begun) backend.end(); }
  }
  @Override public String diagnostics() {
    Fsr1Plan p=plan;
    String dimensions=!broken && !closed && p!=null && p.active()?" | "+p.inputWidth+"x"+p.inputHeight+" → "+p.outputWidth+"x"+p.outputHeight+
        String.format(Locale.US," | texturas estimadas %.1f MiB",p.workingBytes/1048576.0):"";
    long count=frames;
    String timing=count==0?"":" | submissão CPU/GL média/pico "+String.format(Locale.US,"%.2f/%.2f ms",totalNs/1e6/count,maxNs/1e6)+" (não mede GPU/tela)";
    return "AMD FSR 1.0.2: "+status+dimensions+" | RCAS "+sharpness+"%"+timing;
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
  private static final class Pass {
    final GlShader shader;
    final int position,texture,input,output,origin,strength,saturation;
    Pass(Sources source,boolean rcas) {
      GlShader s=new GlShader(source.vertex,rcas?source.rcas:source.easu);
      try {
        position=s.getAttribLocation("in_pos");texture=s.getUniformLocation("source_tex");input=s.getUniformLocation("input_size");
        output=rcas?-1:s.getUniformLocation("output_size");origin=rcas?s.getUniformLocation("viewport_origin"):-1;
        strength=rcas?s.getUniformLocation("rcas_strength"):-1;saturation=rcas?s.getUniformLocation("saturation"):-1;
        shader=s;
      } catch(RuntimeException error) { s.release();throw error; }
    }
  }
  private static final class GlesBackend implements Backend {
    private static final FloatBuffer QUAD=GlUtil.createFloatBuffer(new float[]{-1,-1,1,-1,-1,1,1,1});
    private final Context context;
    private final int[] framebuffer=new int[1],viewport=new int[4],activeTexture=new int[1];
    private boolean scissor,blend;
    private CopyDrawer copy;
    private GlTextureFrameBuffer input,upscaled;
    private Pass easu,rcas;
    GlesBackend(Context context) { this.context=context; }
    @Override public void begin() {
      GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,framebuffer,0);
      GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);
      GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE,activeTexture,0);
      scissor=GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);blend=GLES20.glIsEnabled(GLES20.GL_BLEND);
      GLES20.glDisable(GLES20.GL_SCISSOR_TEST);GLES20.glDisable(GLES20.GL_BLEND);
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
      Sources s=sources(context);
      if(easu==null) easu=new Pass(s,false);
      if(rcas==null) rcas=new Pass(s,true);
      if(copy==null) copy=new CopyDrawer();
      if(input==null) input=new GlTextureFrameBuffer(GLES20.GL_RGBA);
      if(upscaled==null) upscaled=new GlTextureFrameBuffer(GLES20.GL_RGBA);
      resize(input,p.inputWidth,p.inputHeight);resize(upscaled,p.outputWidth,p.outputHeight);
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
      pass.shader.useProgram();QUAD.position(0);
      GLES20.glEnableVertexAttribArray(pass.position);
      GLES20.glVertexAttribPointer(pass.position,2,GLES20.GL_FLOAT,false,0,QUAD);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
      GLES20.glUniform1i(pass.texture,0);GLES20.glUniform2f(pass.input,iw,ih);GLES20.glViewport(x,y,w,h);
    }
    @Override public void easu(Fsr1Plan p) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,upscaled.getFrameBufferId());
      start(easu,input.getTextureId(),p.inputWidth,p.inputHeight,0,0,p.outputWidth,p.outputHeight);
      GLES20.glUniform2f(easu.output,p.outputWidth,p.outputHeight);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("FSR EASU");
    }
    @Override public void rcas(Fsr1Plan p,int x,int y,float strength,float saturation) {
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
      if(scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
      start(rcas,upscaled.getTextureId(),p.outputWidth,p.outputHeight,x,y,p.outputWidth,p.outputHeight);
      GLES20.glUniform2f(rcas.origin,x,y);GLES20.glUniform1f(rcas.strength,strength);GLES20.glUniform1f(rcas.saturation,saturation);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkNoGLES2Error("FSR RCAS");
    }
    @Override public void end() {
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,0);GLES20.glActiveTexture(activeTexture[0]);
      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer[0]);
      GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
      if(scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST);else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
      if(blend) GLES20.glEnable(GLES20.GL_BLEND);else GLES20.glDisable(GLES20.GL_BLEND);
    }
    @Override public void release() {
      RuntimeException failure=null;
      Runnable[] cleanup={()->{if(input!=null) input.release();},()->{if(upscaled!=null) upscaled.release();},
          ()->{if(easu!=null) easu.shader.release();},()->{if(rcas!=null) rcas.shader.release();},()->{if(copy!=null) copy.release();}};
      for(Runnable action:cleanup) try { action.run(); } catch(RuntimeException error) { if(failure==null) failure=error; }
      input=null;upscaled=null;easu=null;rcas=null;copy=null;
      if(failure!=null) throw failure;
    }
  }
}
