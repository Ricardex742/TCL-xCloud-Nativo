#!/usr/bin/env python3
"""Execute the shipped reconstruction variants and compare pixels against controlled references.

Synthetic blur/noise/HUD/upsampling fixtures are deterministic and contain no user images.
This is a correctness/quality test on Mesa, not a device speed or perceptual-quality claim.
"""
from gles_test_context import *
import math
import random
import struct
import zlib
import shutil
import subprocess
import tempfile

ASSET=root/'app/src/main/assets/enhancement/reconstruction.glsl'
VERTEX='attribute vec2 pos;varying highp vec2 tc;uniform mat4 tex_mat;void main(){gl_Position=vec4(pos,0.,1.);tc=(tex_mat*vec4(pos*.5+.5,0.,1.)).xy;}'
IDENTITY=[1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.]
uniform_matrix=gl('glUniformMatrix4fv',None,integer,integer,C.c_ubyte,C.POINTER(C.c_float))
active_texture=gl('glActiveTexture',None,uint)
gen_fbo=gl('glGenFramebuffers',None,integer,C.POINTER(uint))
bind_fbo=gl('glBindFramebuffer',None,uint,uint)
attach_texture=gl('glFramebufferTexture2D',None,uint,uint,uint,uint,integer)
fbo_status=gl('glCheckFramebufferStatus',uint,uint)
get_error=gl('glGetError',uint)
enable=gl('glEnable',None,uint);disable=gl('glDisable',None,uint)
scissor=gl('glScissor',None,integer,integer,integer,integer)
vertices=(C.c_float*8)(-1,-1,1,-1,-1,1,1,1)
checks=0


def verify(condition,message):
    global checks
    assert condition,message
    checks+=1


def program(level,kind='RGB'):
    prefix='precision highp float;varying highp vec2 tc;\n'
    sampler='uniform sampler2D rgb_tex;vec4 sample(vec2 p){return texture2D(rgb_tex,p);}'
    if kind=='OES':
        prefix='#extension GL_OES_EGL_image_external : require\n'+prefix
        sampler='uniform samplerExternalOES oes_tex;vec4 sample(vec2 p){return texture2D(oes_tex,p);}'
    elif kind=='YUV':
        sampler='uniform sampler2D y_tex,u_tex,v_tex;vec4 sample(vec2 p){float y=texture2D(y_tex,p).r,u=texture2D(u_tex,p).r-.5,v=texture2D(v_tex,p).r-.5;return vec4(y+1.4*v,y-.34*u-.71*v,y+1.77*u,1.);}'
    p=create_program();attach_shader(p,compile_source(0x8B31,VERTEX))
    attach_shader(p,compile_source(0x8B30,prefix+sampler+'\n#define RECON_LEVEL '+str(level)+'\n'+ASSET.read_text()))
    bind_attribute(p,0,b'pos');link_program(p)
    ok=integer();program_status(p,0x8B82,C.byref(ok))
    if not ok.value:
        log=C.create_string_buffer(8192);program_log(p,len(log),None,log);raise AssertionError(log.value.decode())
    return p


def texture(data,w,h):
    t=uint();gen_textures(1,C.byref(t));active_texture(0x84C0);bind_texture(0x0DE1,t)
    for key,value in [(0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)]:tex_parameter(0x0DE1,key,value)
    raw=(C.c_ubyte*len(data))(*data) if data is not None else None
    tex_image(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,raw)
    return t.value


def render(p,data,iw,ih,w=None,h=None,strength=.5,matrix=IDENTITY,origin=(0,0),colors=(1.,0.,0.),split=False,logical=None):
    w=w or iw;h=h or ih
    fw,fh=logical or (iw,ih)
    input_tex=texture(data,iw,ih);target=texture(None,w+origin[0],h+origin[1])
    f=uint();gen_fbo(1,C.byref(f));bind_fbo(0x8D40,f);attach_texture(0x8D40,0x8CE0,0x0DE1,target,0)
    assert fbo_status(0x8D40)==0x8CD5
    use_program(p);viewport(*origin,w,h)
    uniform_matrix(uniform_location(p,b'tex_mat'),1,0,(C.c_float*16)(*matrix))
    def pair(key,a,b):uniform2f(uniform_location(p,key.encode()),a,b)
    pair('step_x',matrix[0]/fw,matrix[1]/fw);pair('step_y',matrix[4]/fh,matrix[5]/fh)
    pair('source_size',fw,fh);pair('output_size',w,h);pair('viewport_origin',*origin)
    hx=.5*(abs(matrix[0])/fw+abs(matrix[4])/fh);hy=.5*(abs(matrix[1])/fw+abs(matrix[5])/fh)
    pair('bounds_min',matrix[12]+min(0,matrix[0])+min(0,matrix[4])+hx,matrix[13]+min(0,matrix[1])+min(0,matrix[5])+hy)
    pair('bounds_max',matrix[12]+max(0,matrix[0])+max(0,matrix[4])-hx,matrix[13]+max(0,matrix[1])+max(0,matrix[5])-hy)
    for key,value in [('reconstruction_strength',strength),('saturation',colors[0]),('brightness',colors[1]),('contrast_adjust',colors[2])]:uniform1f(uniform_location(p,key.encode()),value)
    uniform1i(uniform_location(p,b'finish_dither'),0)
    active_texture(0x84C0);bind_texture(0x0DE1,input_tex);uniform1i(uniform_location(p,b'rgb_tex'),0)
    vertex_pointer(0,2,0x1406,0,0,vertices);enable_attribute(0)
    if split:
        # Same GL scissor composition as the native pipeline: source half + treated half.
        enable(0x0C11);scissor(origin[0],origin[1],w//2,h)
        uniform1f(uniform_location(p,b'reconstruction_strength'),0.)
        uniform1f(uniform_location(p,b'saturation'),1.);uniform1f(uniform_location(p,b'brightness'),0.);uniform1f(uniform_location(p,b'contrast_adjust'),0.)
        draw_arrays(5,0,4)
        scissor(origin[0]+w//2,origin[1],w-w//2,h)
        uniform1f(uniform_location(p,b'reconstruction_strength'),strength)
        for key,value in [('saturation',colors[0]),('brightness',colors[1]),('contrast_adjust',colors[2])]:uniform1f(uniform_location(p,key.encode()),value)
    draw_arrays(5,0,4);disable(0x0C11)
    err=get_error();assert err==0,hex(err)
    out=(C.c_ubyte*(w*h*4))();read_pixels(*origin,w,h,0x1908,0x1401,out)
    gl('glDeleteFramebuffers',None,integer,C.POINTER(uint))(1,C.byref(f))
    gl('glDeleteTextures',None,integer,C.POINTER(uint))(2,(uint*2)(input_tex,target))
    return list(out)


def rgba(values):return [channel for v in values for channel in (round(v),round(v),round(v),255)]
def mse(a,b):return sum((a[i]-b[i])**2 for i in range(len(a)) if i%4!=3)/(len(a)*.75)
def png(path,data,w,h):
    def chunk(name,data):return struct.pack('!I',len(data))+name+data+struct.pack('!I',zlib.crc32(name+data))
    rows=b''.join(b'\0'+bytes(data[y*w*4:(y+1)*w*4]) for y in reversed(range(h)))
    path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b''))


programs={level:program(level) for level in (1,2,3)}
for level in programs:
    for kind in ('OES','YUV'):program(level,kind);checks+=1
flat=[54,92,133,255]*1024
hud=rgba([20 if (x//8+y//8)%2 else 235 for y in range(32) for x in range(32)])
for level,p in programs.items():
    for strength in (0.,.5,1.):
        out=render(p,flat,32,32,strength=strength)
        verify(max(abs(a-b) for a,b in zip(out,flat))<=1,'Flat field or color drift')
        out=render(p,hud,32,32,strength=strength)
        verify(max(abs(a-b) for a,b in zip(out,hud))<=1,'New halos or damage to sharp HUD')
    rng=random.Random(270+level);source=[rng.randrange(256) for _ in range(32*32*4)]
    source[3::4]=[255]*1024
    verify(render(p,source,32,32,strength=0.)==source,'Zero strength must preserve the source')
    noise_rng=random.Random(5)
    noise=rgba([120+noise_rng.randrange(-3,4) for _ in range(1024)])
    cleaned=render(p,noise,32,32)
    verify(mse(cleaned,rgba([120]*1024))<=mse(noise,rgba([120]*1024))*1.05,'Noise was amplified')
    verify(abs(sum(cleaned[::4])-sum(noise[::4]))/1024<.5,'Brightness drift in a noisy flat region')
    for origin in ((0,0),(3,5)):
        split=render(p,source,32,32,origin=origin,split=True)
        full=render(p,source,32,32,origin=origin)
        verify(all(split[(y*32+x)*4+k]==(source if x<16 else full)[(y*32+x)*4+k] for y in range(32) for x in range(32) for k in range(4)),'Split must compare the same decoded frame at the correct viewport')

# Blurred slanted edge: a known sharp reference permits measuring an actual restoration gain.
w,h=96,64
reference=rgba([45 if x+.35*y<57 else 205 for y in range(h) for x in range(w)])
kernel=[math.exp(-i*i/(2*.95*.95)) for i in range(-3,4)];total=sum(kernel);kernel=[v/total for v in kernel]
def blur(a):
    first=[sum(a[(y*w+min(w-1,max(0,x+i-3)))*4]*v for i,v in enumerate(kernel)) for y in range(h) for x in range(w)]
    return rgba([sum(first[min(h-1,max(0,y+i-3))*w+x]*v for i,v in enumerate(kernel)) for y in range(h) for x in range(w)])
blurred=blur(reference);metrics={}
for level,p in programs.items():
    restored=render(p,blurred,w,h)
    improvement=10*math.log10(mse(blurred,reference)/max(mse(restored,reference),1e-9))
    metrics[str(level)]={'slanted_edge_psnr_gain_db':round(improvement,3)}
    verify(improvement>.25,f'Level {level}: no restoration gain against a known blurred edge ({improvement:.3f} dB)')
    verify(max(abs(a-b) for a,b in zip(restored,blurred))>=3,'Detail-on must visibly alter a soft edge')
    verify(min(restored[::4])>=45 and max(restored[::4])<=205,'Edge reconstruction introduced ringing')

# Holdout: three independent soft text/texture frequency fixtures, not just a single edge.
for frequency in (.08,.13,.21):
    clean=rgba([128+55*math.sin(x*frequency*math.tau)+15*math.sin(y*.09*math.tau) for y in range(h) for x in range(w)])
    soft=blur(clean)
    for level,p in programs.items():
        restored=render(p,soft,w,h)
        verify(mse(restored,clean)<mse(soft,clean),f'Detail restoration worsened the holdout frequency {frequency}, level {level}')

# The source transform, crop borders, viewport origin and enlargement are independent of filtering.
flipped=[-1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.,0.,1.,0.,0.,1.]
for level,p in programs.items():
    out=render(p,blurred,w,h);mirror=render(p,blurred,w,h,matrix=flipped)
    verify(all(abs(out[(y*w+x)*4+k]-mirror[(y*w+w-1-x)*4+k])<=1 for y in range(h) for x in range(w) for k in range(4)),'Decoder mirror transform changed reconstruction')
    enlarged=render(p,flat,32,32,w=64,h=64,origin=(3,5))
    verify(max(abs(a-b) for a,b in zip(enlarged,[54,92,133,255]*4096))<=1,'Enlargement/viewport changed a flat color')
    enlarged_hud=render(p,hud,32,32,w=64,h=64)
    verify(min(enlarged_hud[::4])>=20 and max(enlarged_hud[::4])<=235,'Enlargement introduced halos around HUD edges')
    enlarged_edge=render(p,blurred,w,h,w=w*2,h=h*2)
    verify(min(enlarged_edge[::4])>=45 and max(enlarged_edge[::4])<=205,'Cubic reconstruction and detail stacked their overshoot')
colored=[v for value in blurred[::4] for v in (value-20,value,value+20,255)]
for p in programs.values():
    out=render(p,colored,w,h)
    verify(all(abs(out[i+1]-out[i]-20)<=1 and abs(out[i+2]-out[i+1]-20)<=1 for i in range(0,len(out),4)),'Luma restoration changed chroma')

padding=[v for y in range(32) for x in range(32) for v in ((255,0,0,255) if x<4 or x>=28 else (60,90,120,255))]
crop=[.75,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.,0.,.125,0.,0.,1.]
rotation=[0.,1.,0.,0.,-1.,0.,0.,0.,0.,0.,1.,0.,1.,0.,0.,1.]
for level,p in programs.items():
    out=render(p,padding,32,32,w=48,h=64,logical=(24,32),matrix=crop,strength=1.)
    verify(max(abs(a-b) for a,b in zip(out,[60,90,120,255]*(48*64)))<=1,'Padding outside the decoder crop contaminated reconstruction')
    normal=render(p,blurred,w,h)
    rotated=render(p,blurred,w,h,w=h,h=w,logical=(h,w),matrix=rotation)
    verify(all(abs(rotated[(y*h+x)*4+k]-normal[(x*w+w-1-y)*4+k])<=1 for y in range(w) for x in range(h) for k in range(4)),'Decoder rotation changed reconstruction')

# A real H.264 encode/decode exercises quantization, temporal prediction and video continuity.
# This synthetic moving edge is deliberately separate from the static test above.
assert shutil.which('ffmpeg'),'Install ffmpeg to run the compressed-video quality regression'
clean_frames=[rgba([45 if x+.35*y<52+frame*.65 else 205 for y in range(h) for x in range(w)]) for frame in range(12)]
video_metrics={}
with tempfile.TemporaryDirectory(prefix='reconstruction-video-') as directory:
    for name,inputs in [('compressed_only',clean_frames),('blurred_and_compressed',[blur(f) for f in clean_frames])]:
        target=Path(directory)/(name+'.mp4')
        encoded=subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','rawvideo','-pix_fmt','rgba','-s',f'{w}x{h}','-r','30','-i','pipe:0',
            '-c:v','libx264','-preset','veryfast','-crf','34','-pix_fmt','yuv420p','-g','12','-threads','1','-an',str(target)],input=b''.join(bytes(f) for f in inputs),capture_output=True)
        assert encoded.returncode==0,encoded.stderr.decode()[-2000:]
        decoded=subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',str(target),'-f','rawvideo','-pix_fmt','rgba','-threads','1','pipe:1'],capture_output=True,check=True).stdout
        frame_bytes=w*h*4;verify(len(decoded)==len(inputs)*frame_bytes,'H.264 fixture lost frames')
        received=[list(decoded[i:i+frame_bytes]) for i in range(0,len(decoded),frame_bytes)]
        baseline=sum(mse(f,ref) for f,ref in zip(received,clean_frames))/len(received)
        video_metrics[name]={}
        for level,p in programs.items():
            reconstructed=[render(p,f,w,h) for f in received]
            error=sum(mse(f,ref) for f,ref in zip(reconstructed,clean_frames))/len(received)
            gain=10*math.log10(baseline/max(error,1e-9));video_metrics[name][str(level)]=round(gain,3)
            # Encoding can differ across libx264 versions. Require a positive restoration gain
            # for the blurred fixture; explicitly bound, and report, loss on already sharp input.
            verify(gain>(0. if name=='blurred_and_compressed' else -.5),f'H.264 quality regression: {name}, level {level}, PSNR gain {gain:.3f} dB')

report_dir=root/'app/build/reports/reconstruction';report_dir.mkdir(parents=True,exist_ok=True)
restored=render(programs[2],blurred,w,h)
png(report_dir/'synthetic-original.png',blurred,w,h);png(report_dir/'synthetic-reconstructed.png',restored,w,h)
comparison=[]
for y in range(h):comparison+=blurred[y*w*4:(y+1)*w*4]+restored[y*w*4:(y+1)*w*4]
png(report_dir/'synthetic-ab.png',comparison,w*2,h)
report={'checks':checks,'device':'Mesa software GPU; not an Android speed measurement','synthetic_reference_metrics':metrics,
    'h264_moving_fixture_psnr_gain_db':video_metrics,'guarantees':'Only these controlled fixtures. No general perceptual superiority or 4K/60 claim.'}
(report_dir/'quality.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2));terminate(display)
