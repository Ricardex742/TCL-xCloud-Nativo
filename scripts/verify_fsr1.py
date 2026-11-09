#!/usr/bin/env python3
"""Validate the app FSR shaders against the pinned AMD reference in headless Mesa GLES.

This test reads pixels only in the standalone software test, never in the Android render loop.
Requires libegl1, libegl-mesa0 and libgl1-mesa-dri; no Python packages are needed.
"""
import ctypes as C
import ctypes.util
import json
import os
import re
from pathlib import Path

os.environ.setdefault("EGL_PLATFORM", "surfaceless")
os.environ.setdefault("LIBGL_ALWAYS_SOFTWARE", "1")
os.environ.setdefault("MESA_SHADER_CACHE_DISABLE", "true")
root = Path(__file__).resolve().parent.parent
egl = C.CDLL(ctypes.util.find_library("EGL"))


def egl_function(name, result, *args):
    fn = getattr(egl, name)
    fn.restype, fn.argtypes = result, args
    return fn


ptr, integer, uint, boolean = C.c_void_p, C.c_int, C.c_uint, C.c_uint
get_display = egl_function("eglGetDisplay", ptr, ptr)
initialize = egl_function("eglInitialize", boolean, ptr, C.POINTER(integer), C.POINTER(integer))
choose_config = egl_function("eglChooseConfig", boolean, ptr, C.POINTER(integer), C.POINTER(ptr), integer, C.POINTER(integer))
bind_api = egl_function("eglBindAPI", boolean, uint)
create_surface = egl_function("eglCreatePbufferSurface", ptr, ptr, ptr, C.POINTER(integer))
create_context = egl_function("eglCreateContext", ptr, ptr, ptr, ptr, C.POINTER(integer))
make_current = egl_function("eglMakeCurrent", boolean, ptr, ptr, ptr, ptr)
get_proc = egl_function("eglGetProcAddress", ptr, C.c_char_p)
terminate = egl_function("eglTerminate", boolean, ptr)
display = get_display(None)
assert initialize(display, None, None), "Headless EGL initialization failed"
assert bind_api(0x30A0), "EGL OpenGL ES API unavailable"
attributes = (integer * 13)(0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3033, 1, 0x3040, 4, 0x3038)
config, count = ptr(), integer()
assert choose_config(display, attributes, C.byref(config), 1, C.byref(count)) and count.value, "No ES2 pbuffer configuration"
surface = create_surface(display, config, (integer * 5)(0x3057, 8, 0x3056, 8, 0x3038))
context = create_context(display, config, None, (integer * 3)(0x3098, 3, 0x3038))
assert surface and context and make_current(display, surface, surface, context), "Cannot create software ES context"


def gl(name, result, *args):
    address = get_proc(name.encode())
    assert address, f"Missing {name}"
    return C.CFUNCTYPE(result, *args)(address)


create_shader = gl("glCreateShader", uint, uint)
shader_source = gl("glShaderSource", None, uint, integer, C.POINTER(C.c_char_p), C.POINTER(integer))
compile_shader = gl("glCompileShader", None, uint)
shader_status = gl("glGetShaderiv", None, uint, uint, C.POINTER(integer))
shader_log = gl("glGetShaderInfoLog", None, uint, integer, C.POINTER(integer), ptr)
create_program = gl("glCreateProgram", uint)
attach_shader = gl("glAttachShader", None, uint, uint)
bind_attribute = gl("glBindAttribLocation", None, uint, uint, C.c_char_p)
link_program = gl("glLinkProgram", None, uint)
program_status = gl("glGetProgramiv", None, uint, uint, C.POINTER(integer))
program_log = gl("glGetProgramInfoLog", None, uint, integer, C.POINTER(integer), ptr)
use_program = gl("glUseProgram", None, uint)
uniform_location = gl("glGetUniformLocation", integer, uint, C.c_char_p)
uniform1f = gl("glUniform1f", None, integer, C.c_float)
uniform1i = gl("glUniform1i", None, integer, integer)
uniform2f = gl("glUniform2f", None, integer, C.c_float, C.c_float)
viewport = gl("glViewport", None, integer, integer, integer, integer)
gen_textures = gl("glGenTextures", None, integer, C.POINTER(uint))
bind_texture = gl("glBindTexture", None, uint, uint)
tex_parameter = gl("glTexParameteri", None, uint, uint, integer)
tex_image = gl("glTexImage2D", None, uint, integer, integer, integer, integer, integer, uint, uint, ptr)
vertex_pointer = gl("glVertexAttribPointer", None, uint, integer, uint, C.c_ubyte, integer, ptr)
enable_attribute = gl("glEnableVertexAttribArray", None, uint)
draw_arrays = gl("glDrawArrays", None, uint, integer, integer)
read_pixels = gl("glReadPixels", None, integer, integer, integer, integer, uint, uint, ptr)
get_string = gl("glGetString", C.c_char_p, uint)


def compile_source(kind, text):
    shader = create_shader(kind)
    encoded = C.c_char_p(text.encode())
    shader_source(shader, 1, C.byref(encoded), None)
    compile_shader(shader)
    status = integer()
    shader_status(shader, 0x8B81, C.byref(status))
    if not status.value:
        log = C.create_string_buffer(8192)
        shader_log(shader, len(log), None, log)
        raise AssertionError(log.value.decode())
    return shader


import hashlib
import math
import random

gen_framebuffers=gl("glGenFramebuffers",None,integer,C.POINTER(uint))
bind_framebuffer=gl("glBindFramebuffer",None,uint,uint)
framebuffer_texture=gl("glFramebufferTexture2D",None,uint,uint,uint,uint,integer)
framebuffer_status=gl("glCheckFramebufferStatus",uint,uint)
delete_textures=gl("glDeleteTextures",None,integer,C.POINTER(uint))
delete_framebuffers=gl("glDeleteFramebuffers",None,integer,C.POINTER(uint))
clear_color=gl("glClearColor",None,C.c_float,C.c_float,C.c_float,C.c_float)
clear=gl("glClear",None,uint)
uniform_matrix=gl("glUniformMatrix4fv",None,integer,integer,C.c_ubyte,C.POINTER(C.c_float))
get_error=gl("glGetError",uint)
active_texture=gl("glActiveTexture",None,uint)
assets=root/"app/src/main/assets/fsr1"
third=root/"third_party/amd-fsr1"
hashes={"ffx_fsr1.h":"93c3922362ea7fc99cbcc698ca30c98de4f8c246d1fbb0b09e015ddef38ce3a5",
        "ffx_a.h":"f60e2722fcd13989523b9164d776ab382b3692791767f3bf8bb19967f763f3fb",
        "LICENSE":"db089274ce766da70f5b7d791029c3486f9f9e27c8c79c652689603d3192e802"}
for name,digest in hashes.items():
    assert hashlib.sha256((third/name).read_bytes()).hexdigest()==digest,(name,"upstream changed")
assert (assets/"LICENSE.txt").read_bytes()==(third/"LICENSE").read_bytes()


def program(fragment,vertex=None):
    p=create_program()
    attach_shader(p,compile_source(0x8B31,vertex or (assets/"fullscreen.vert").read_text()))
    attach_shader(p,compile_source(0x8B30,fragment))
    bind_attribute(p,0,b"in_pos")
    bind_attribute(p,1,b"in_tc")
    link_program(p)
    status=integer();program_status(p,0x8B82,C.byref(status))
    if not status.value:
        log=C.create_string_buffer(8192);program_log(p,len(log),None,log)
        raise AssertionError(log.value.decode())
    return p


app_easu=program((assets/"easu.frag").read_text())
app_rcas=program((assets/"rcas.frag").read_text())
reference_vertex="#version 310 es\nin vec2 in_pos; void main(){gl_Position=vec4(in_pos,0.,1.);}\n"


def reference(kind,exact):
    prefix="#version 310 es\nprecision highp float; precision highp int;\n#define A_GPU 1\n#define A_GLSL 1\n"
    prefix+=(third/"ffx_a.h").read_text()
    if exact:
        prefix+="\n#define APrxLoRcpF1(a) (1.0/max((a),1.0e-6))\n#define APrxLoRsqF1(a) inversesqrt(max((a),1.0e-6))\n#define APrxMedRcpF1(a) (1.0/max((a),1.0e-6))\n"
    prefix+="\n#define FSR_"+kind+"_F 1\n#define FSR_RCAS_DENOISE 1\n"
    # ES requires an explicit unsigned zero in these three setup assignments.
    # The vendored upstream file remains byte-for-byte unchanged.
    header=(third/"ffx_fsr1.h").read_text().replace("con3[2]=con3[3]=0;","con3[2]=con3[3]=0u;")
    header=header.replace("con[2]=0;","con[2]=0u;").replace("con[3]=0;","con[3]=0u;")
    prefix+=header
    prefix+="\nuniform sampler2D source_tex; uniform vec2 input_size; uniform vec2 output_size;\n"
    prefix+="uniform float rcas_strength; uniform float saturation; out vec4 result;\n"
    if kind=="EASU":
        prefix+="""
        AF4 FsrEasuRF(AF2 p){return textureGather(source_tex,p,0);}
        AF4 FsrEasuGF(AF2 p){return textureGather(source_tex,p,1);}
        AF4 FsrEasuBF(AF2 p){return textureGather(source_tex,p,2);}
        void main(){uvec4 a,b,c,d;FsrEasuCon(a,b,c,d,input_size.x,input_size.y,input_size.x,input_size.y,output_size.x,output_size.y);
          vec3 color;FsrEasuF(color,uvec2(gl_FragCoord.xy),a,b,c,d);result=vec4(color,1.);}
        """
    else:
        prefix+="""
        AF4 FsrRcasLoadF(ASU2 p){return texelFetch(source_tex,clamp(p,ivec2(0),ivec2(input_size)-1),0);}
        void FsrRcasInputF(inout AF1 r,inout AF1 g,inout AF1 b){}
        void main(){vec3 color;
          if(rcas_strength<=0.) color=FsrRcasLoadF(ivec2(gl_FragCoord.xy)).rgb;
          else {uvec4 c;FsrRcasCon(c,-log2(rcas_strength));FsrRcasF(color.r,color.g,color.b,uvec2(gl_FragCoord.xy),c);}
          float l=dot(color,vec3(.299,.587,.114));result=vec4(clamp(mix(vec3(l),color,saturation),0.,1.),1.);}
        """
    return program(prefix,reference_vertex)


exact_easu=reference("EASU",True)
exact_rcas=reference("RCAS",True)
stock_easu=reference("EASU",False)
stock_rcas=reference("RCAS",False)
vertices=(C.c_float*8)(-1,-1,1,-1,-1,1,1,1)
vertex_pointer(0,2,0x1406,0,0,vertices);enable_attribute(0)
texcoords=(C.c_float*8)(0,0,1,0,0,1,1,1)
vertex_pointer(1,2,0x1406,0,0,texcoords);enable_attribute(1)


def texture(w,h,data=None,linear=False):
    t=uint();gen_textures(1,C.byref(t));bind_texture(0x0DE1,t)
    for key,value in [(0x2801,0x2601 if linear else 0x2600),(0x2800,0x2601 if linear else 0x2600),(0x2802,0x812F),(0x2803,0x812F)]:
        tex_parameter(0x0DE1,key,value)
    pixels=(C.c_ubyte*len(data))(*data) if data is not None else None
    tex_image(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,pixels)
    assert get_error()==0,"Texture allocation failed"
    return t


def draw(p,source,iw,ih,ow,oh,strength=.5,saturation=1.,origin=(0,0),matrix=None,brightness=0.,contrast=0.):
    x,y=origin;tw,th=ow+x+2,oh+y+2
    result=texture(tw,th)
    fbo=uint();gen_framebuffers(1,C.byref(fbo));bind_framebuffer(0x8D40,fbo)
    framebuffer_texture(0x8D40,0x8CE0,0x0DE1,result,0)
    assert framebuffer_status(0x8D40)==0x8CD5,"Incomplete render target"
    viewport(0,0,tw,th);clear_color(7/255,13/255,19/255,1);clear(0x4000)
    use_program(p);bind_texture(0x0DE1,source)
    uniform1i(uniform_location(p,b"source_tex"),0)
    uniform1i(uniform_location(p,b"tex"),0)
    uniform1i(uniform_location(p,b"y_tex"),0)
    uniform1i(uniform_location(p,b"u_tex"),1)
    uniform1i(uniform_location(p,b"v_tex"),2)
    uniform2f(uniform_location(p,b"input_size"),iw,ih)
    uniform2f(uniform_location(p,b"output_size"),ow,oh)
    uniform2f(uniform_location(p,b"viewport_origin"),x,y)
    uniform1f(uniform_location(p,b"rcas_strength"),strength)
    uniform1f(uniform_location(p,b"saturation"),saturation)
    uniform1f(uniform_location(p,b"brightness"),brightness)
    uniform1f(uniform_location(p,b"contrast_adjust"),contrast)
    if matrix is not None:uniform_matrix(uniform_location(p,b"tex_mat"),1,0,(C.c_float*16)(*matrix))
    viewport(x,y,ow,oh);draw_arrays(5,0,4)
    pixels=(C.c_ubyte*(ow*oh*4))();read_pixels(x,y,ow,oh,0x1908,0x1401,pixels)
    assert get_error()==0,"GL draw/readback failed"
    # The padding outside the viewport must remain untouched.
    sentinel=(C.c_ubyte*4)();read_pixels(tw-1,th-1,1,1,0x1908,0x1401,sentinel)
    assert list(sentinel)==[7,13,19,255],list(sentinel)
    bind_framebuffer(0x8D40,0);delete_framebuffers(1,C.byref(fbo));delete_textures(1,C.byref(result))
    return list(pixels)


def run(easu,rcas,data,iw,ih,ow,oh,strength=.5,saturation=1.,origin=(0,0)):
    source=texture(iw,ih,data)
    enlarged=draw(easu,source,iw,ih,ow,oh)
    delete_textures(1,C.byref(source))
    intermediate=texture(ow,oh,enlarged)
    result=draw(rcas,intermediate,ow,oh,ow,oh,strength,saturation,origin)
    delete_textures(1,C.byref(intermediate))
    return enlarged,result


checks=0;comparisons=[]
for flat in ([0,0,0],[255,255,255],[64,64,64],[80,140,210]):
    for strength in (0.,.5,1.):
        enlarged,result=run(app_easu,app_rcas,(flat+[255])*35,7,5,17,13,strength)
        assert all(abs(v-flat[i%4])<=1 for i,v in enumerate(result) if i%4!=3),(flat,strength)
        assert all(v==255 for v in result[3::4])
        checks+=1

rng=random.Random(31)
for iw,ih,ow,oh in [(8,6,16,12),(8,6,12,9),(7,5,17,11),(5,7,9,15)]:
    patterns={
        "noise":[rng.randrange(24,232) if i%4!=3 else 255 for i in range(iw*ih*4)],
        "diagonal":[v for y in range(ih) for x in range(iw) for v in ([220,180,60,255] if x*ih>y*iw else [35,65,185,255])],
        "gradient":[v for y in range(ih) for x in range(iw) for v in [int(30+180*x/(iw-1)),int(30+180*y/(ih-1)),120,255]],
    }
    for name,data in patterns.items():
        a,actual=run(app_easu,app_rcas,data,iw,ih,ow,oh)
        b,expected=run(exact_easu,exact_rcas,data,iw,ih,ow,oh)
        _,stock=run(stock_easu,stock_rcas,data,iw,ih,ow,oh)
        easu_error=max(abs(x-y) for x,y in zip(a,b))
        exact_error=max(abs(x-y) for x,y in zip(actual,expected))
        stock_mean=sum(abs(x-y) for x,y in zip(actual,stock))/len(actual)
        assert easu_error<=2 and exact_error<=4,(name,(iw,ih,ow,oh),easu_error,exact_error)
        comparisons.append(dict(pattern=name,input=[iw,ih],output=[ow,oh],easu_max_error=easu_error,
                                exact_max_error=exact_error,stock_mean_error=round(stock_mean,4)))
        _,shifted=run(app_easu,app_rcas,data,iw,ih,ow,oh,origin=(3,5))
        assert shifted==actual,"RCAS viewport origin was applied incorrectly"
        checks+=2

# Color transforms/rotation are resolved in RGB preparation using the original WebRTC matrix.
generated=root/"app/build/reports/dashboard"
assert (generated/"fsr1-copy.vert").exists(),"Run FsrCopyShaderTest before the GPU validation"
copy_vertex=(generated/"fsr1-copy.vert").read_text()
copy_programs={kind:program((generated/f"fsr1-copy-{kind}.frag").read_text(),copy_vertex) for kind in ("rgb","oes","yuv")}
copy_program=copy_programs["rgb"]
colors=[[40,60,180,255],[80,200,60,255],[210,90,100,255],[190,170,40,255]]
iw,ih=8,6
data=[v for y in range(ih) for x in range(iw) for v in colors[(2 if y>=ih//2 else 0)+(1 if x>=iw//2 else 0)]]
transforms=[([1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1],iw,ih,[0,1,2,3]),
            ([1,0,0,0,0,-1,0,0,0,0,1,0,0,1,0,1],iw,ih,[2,3,0,1]),
            ([-1,0,0,0,0,1,0,0,0,0,1,0,1,0,0,1],iw,ih,[1,0,3,2]),
            ([0,-1,0,0,1,0,0,0,0,0,1,0,0,1,0,1],ih,iw,[2,0,3,1])]
source=texture(iw,ih,data)
for matrix,cw,ch,indices in transforms:
    copied=draw(copy_program,source,iw,ih,cw,ch,matrix=matrix)
    _,result=run(app_easu,app_rcas,copied,cw,ch,cw*2,ch*2)
    for x,y,index in [(0,0,indices[0]),(cw*2-1,0,indices[1]),(0,ch*2-1,indices[2]),(cw*2-1,ch*2-1,indices[3])]:
        pixel=result[(y*cw*2+x)*4:][:4]
        assert all(abs(a-b)<=1 for a,b in zip(pixel,colors[index])),(matrix,pixel,colors[index])
    checks+=1
delete_textures(1,C.byref(source))

# Execute the actual highp WebRTC YUV adapter before EASU/RCAS too.
def plane(w,h,value):
    t=texture(w,h)
    pixels=(C.c_ubyte*(w*h))(*([value]*(w*h)))
    tex_image(0x0DE1,0,0x1909,w,h,0,0x1909,0x1401,pixels)
    return t
ytex=plane(8,6,126);utex=plane(4,3,110);vtex=plane(4,3,160)
active_texture(0x84C1);bind_texture(0x0DE1,utex)
active_texture(0x84C2);bind_texture(0x0DE1,vtex)
active_texture(0x84C0)
copied=draw(copy_programs["yuv"],ytex,8,6,8,6,matrix=transforms[0][0])
yy=126/255*1.16438;u=110/255;v=160/255
expected=[round(c*255) for c in (yy+1.59603*v-.874202,yy-.391762*u-.812968*v+.531668,yy+2.01723*u-1.08563)]
_,result=run(app_easu,app_rcas,copied,8,6,16,12)
assert all(abs(value-expected[i%4])<=2 for i,value in enumerate(result) if i%4!=3)
for t in (ytex,utex,vtex):delete_textures(1,C.byref(t))
checks+=1

# Saturation and RCAS zero strength are tested after enlargement, without a second unsharp pass.
_,gray=run(app_easu,app_rcas,[80,140,210,255]*24,6,4,12,8,saturation=0.)
assert all(abs(gray[i]-gray[i+1])<=1 and abs(gray[i]-gray[i+2])<=1 for i in range(0,len(gray),4))
checks+=1
# Color adjustments share the existing RCAS pass; the neutral FSR reference checks remain above.
source=texture(4,4,[80,140,210,255]*16)
for brightness,contrast in [(.1,0.),(0.,.25),(-.2,-.25)]:
    pixels=draw(app_rcas,source,4,4,4,4,strength=0.,brightness=brightness,contrast=contrast)
    expected=[round(max(0.,min(1.,(v/255.-.5)*(1.+contrast)+.5+brightness))*255) for v in [80,140,210]]
    assert all(abs(a-b)<=2 for a,b in zip(pixels[:3],expected)),(pixels[:3],expected)
    checks+=1
delete_textures(1,C.byref(source))
report=dict(renderer=get_string(0x1F01).decode(),version=get_string(0x1F02).decode(),checks=checks,
            upstream_commit="a21ffb8f6c13233ba336352bdff293894c706575",comparisons=comparisons,
            note="Readback is test-only. Software timings do not measure Android GPU or streaming latency.")
output=root/"app/build/reports/dashboard";output.mkdir(parents=True,exist_ok=True)
(output/"fsr1-validation.json").write_text(json.dumps(report,indent=2)+"\n")
summary=f"{report['version']} / {report['renderer']}\nFSR EASU/RCAS and actual WebRTC highp OES/RGB/YUV copy shaders compiled; pinned AMD stock/exact reference shaders compiled.\n{checks} pixel-output/reference/rotation/border/viewport/color checks passed.\n"
(output/"fsr1-validation.txt").write_text(summary)
print(summary,end="")
make_current(display,None,None,None);terminate(display)
