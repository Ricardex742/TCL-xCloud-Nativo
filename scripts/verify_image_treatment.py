#!/usr/bin/env python3
"""Run the shipped GLES2 treatment, SGSR, temporal and neural shaders on Mesa.

Pixel readback is confined to this offline test; the Android render loop never reads pixels.
"""
from gles_test_context import *
import math
import struct
import json

ASSETS = root / "app/src/main/assets/enhancement"
VERTEX = "attribute vec2 pos;varying highp vec2 tc;uniform mat4 tex_mat;void main(){gl_Position=vec4(pos,0.,1.);tc=(tex_mat*vec4(pos*.5+.5,0.,1.)).xy;}"
SAMPLER = "uniform sampler2D rgb_tex;vec4 sample(vec2 p){return texture2D(rgb_tex,p);}"
uniform_matrix = gl("glUniformMatrix4fv", None, integer, integer, C.c_ubyte, C.POINTER(C.c_float))
active_texture = gl("glActiveTexture", None, uint)
gen_fbo = gl("glGenFramebuffers", None, integer, C.POINTER(uint))
bind_fbo = gl("glBindFramebuffer", None, uint, uint)
attach_texture = gl("glFramebufferTexture2D", None, uint, uint, uint, uint, integer)
fbo_status = gl("glCheckFramebufferStatus", uint, uint)
get_error = gl("glGetError", uint)
IDENTITY = [1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.]
vertices = (C.c_float * 8)(-1,-1,1,-1,-1,1,1,1)
checks = 0


def program(fragment, adapter=False, kind="RGB"):
    if adapter:
        sampler = SAMPLER
        prefix = "precision highp float;varying highp vec2 tc;\n"
        if kind == "OES":
            prefix = "#extension GL_OES_EGL_image_external : require\n" + prefix
            sampler = "uniform samplerExternalOES oes_tex;vec4 sample(vec2 p){return texture2D(oes_tex,p);}"
        elif kind == "YUV":
            sampler = "uniform sampler2D y_tex,u_tex,v_tex;vec4 sample(vec2 p){float y=texture2D(y_tex,p).r,u=texture2D(u_tex,p).r-.5,v=texture2D(v_tex,p).r-.5;return vec4(y+1.4*v,y-.34*u-.71*v,y+1.77*u,1.);}"
        fragment = prefix + sampler + fragment.replace("/* ADAPTIVE_SHARPEN */", (ASSETS / "adaptive.glsl").read_text())
    else:
        fragment = fragment.replace("varying vec2 tc;", "varying highp vec2 tc;")
    p = create_program()
    attach_shader(p, compile_source(0x8B31, VERTEX));attach_shader(p, compile_source(0x8B30, fragment))
    bind_attribute(p, 0, b"pos");link_program(p)
    status = integer();program_status(p, 0x8B82, C.byref(status))
    if not status.value:
        log = C.create_string_buffer(8192);program_log(p, len(log), None, log)
        raise AssertionError(log.value.decode())
    return p


def texture(data, w, h, half=False):
    t=uint();gen_textures(1,C.byref(t));active_texture(0x84C0);bind_texture(0x0DE1,t)
    for k,v in [(0x2801,0x2600 if half else 0x2601),(0x2800,0x2600 if half else 0x2601),(0x2802,0x812F),(0x2803,0x812F)]:tex_parameter(0x0DE1,k,v)
    pixels=(C.c_ubyte*len(data))(*data) if data is not None else None
    tex_image(0x0DE1,0,0x1908,w,h,0,0x1908,0x8D61 if half else 0x1401,pixels)
    return t.value


def framebuffer(t):
    f=uint();gen_fbo(1,C.byref(f));bind_fbo(0x8D40,f)
    attach_texture(0x8D40,0x8CE0,0x0DE1,t,0)
    assert fbo_status(0x8D40)==0x8CD5,"Half-float/reference framebuffer unavailable"
    return f.value


def draw(p, textures, w, h, params=None, target=0, matrix=IDENTITY, origin=(0,0), read=True):
    params=params or {};bind_fbo(0x8D40,target);use_program(p);viewport(origin[0],origin[1],w,h)
    uniform_matrix(uniform_location(p,b"tex_mat"),1,0,(C.c_float*16)(*matrix))
    defaults={"sharpness":0.,"saturation":1.,"brightness":0.,"contrast_adjust":0.,"deband":0.,"cleanup":0.,"chroma":0.,"antialias":0.,"detail":0.,"debandTaps":4.,"strength":0.}
    for name,value in (defaults|params).items():
        if isinstance(value,(bool,int)):uniform1i(uniform_location(p,name.encode()),value)
        else:uniform1f(uniform_location(p,name.encode()),value)
    source_w,source_h=params.get("input_w",w),params.get("input_h",h)
    for name,a,b in [("step_x",matrix[0]/source_w,matrix[1]/source_w),("step_y",matrix[4]/source_h,matrix[5]/source_h),
                     ("source_origin",matrix[12]+.5*(matrix[0]/source_w+matrix[4]/source_h),matrix[13]+.5*(matrix[1]/source_w+matrix[5]/source_h)),
                     ("source_size",source_w,source_h),("frame_size",source_w,source_h),("output_size",w,h),("viewport_origin",*origin)]:
        uniform2f(uniform_location(p,name.encode()),a,b)
    for unit,(name,t) in enumerate(textures.items()):
        active_texture(0x84C0+unit);bind_texture(0x0DE1,t);uniform1i(uniform_location(p,name.encode()),unit)
    vertex_pointer(0,2,0x1406,0,0,vertices);enable_attribute(0);draw_arrays(5,0,4)
    error=get_error();assert error==0,hex(error)
    if not read:
        return None
    result=(C.c_ubyte*(w*h*4))();read_pixels(*origin,w,h,0x1908,0x1401,result)
    return list(result)


def verify(condition, message):
    global checks
    assert condition,message
    checks+=1


spatial_source=(ASSETS/"spatial.glsl").read_text();sgsr_source=(ASSETS/"sgsr.glsl").read_text()
for source in (spatial_source,sgsr_source):
    for kind in ("RGB","OES","YUV"):
        program(source,True,kind);checks+=1
spatial=program(spatial_source,True);sgsr=program(sgsr_source,True)
temporal=program((ASSETS/"temporal.frag").read_text())
features=[program((ASSETS/f"neural-{i}.frag").read_text()) for i in (0,1)]
neural=program((ASSETS/"neural-apply.frag").read_text())
flat=[80,100,120,255]*256;flat_tex=texture(flat,16,16)
for mode in (0,1,2):
    out=draw(spatial,{"rgb_tex":flat_tex},16,16,{"finish_image":1,"selective":1,"quantize":1,"sharpen_mode":mode,"sharpness":1.,"deband":.5,"cleanup":.5,"chroma":.75})
    verify(max(abs(a-b) for a,b in zip(out,flat))<=1,"Flat color must remain stable with sharpening/cleanup/deband")
    out=draw(sgsr,{"rgb_tex":flat_tex},32,32,{"input_w":16,"input_h":16,"sharpness":.5})
    verify(max(abs(a-b) for a,b in zip(out,[80,100,120,255]*1024))<=1,"SGSR must preserve flat colors")

pattern=[]
for y in range(16):
    for x in range(16):
        v=20 if x<8 else 230;pattern += [v,v,v,255]
edge_tex=texture(pattern,16,16)
for mode in (0,1,2):
    out=draw(spatial,{"rgb_tex":edge_tex},16,16,{"finish_image":1,"selective":1,"quantize":1,"sharpen_mode":mode,"sharpness":.5,"cleanup":1.,"deband":1.,"chroma":1.})
    difference=max(abs(a-b) for a,b in zip(out,pattern))
    if mode>0:
        verify(difference<=7,f"Adaptive sharpening must protect HUD edges (mode={mode}, max error={difference})")
    else:
        verify(difference>7,"The legacy RGB unsharp control must exercise the test's halo detector")
for origin in ((0,0),(3,5)):
    out=draw(sgsr,{"rgb_tex":edge_tex},32,32,{"input_w":16,"input_h":16,"sharpness":.5},origin=origin)
    for y in range(32):
        verify(abs(out[(y*32+2)*4]-20)<=1 and abs(out[(y*32+29)*4]-230)<=1,"SGSR source alignment/border failed")
    verify(all(out[i]==255 for i in range(3,len(out),4)),"SGSR alpha must remain opaque")

# True bypass and transformed input at zero sharpening.
plain=draw(spatial,{"rgb_tex":edge_tex},16,16,{"finish_image":1,"sharpen_mode":0,"quantize":0})
verify(plain==pattern,"Zero treatment must preserve all pixels")
flip=[-1.,0.,0.,0.,0.,1.,0.,0.,0.,0.,1.,0.,1.,0.,0.,1.]
out=draw(sgsr,{"rgb_tex":edge_tex},16,16,{"input_w":16,"input_h":16,"sharpness":0.},matrix=flip)
verify(all(abs(out[(y*16+x)*4]-pattern[(y*16+15-x)*4])<=1 for y in range(16) for x in range(16)),"SGSR must honor the decoder transform")

# Validate the exact shader variants exported by the Java runtime's unit test.
# Disabled effects must preserve output while disappearing from the compiled variant.
for mask in range(16):
    emitted=root / "app/build/reports/treatment-shaders" / f"spatial-{mask}.glsl"
    if not emitted.exists():
        raise AssertionError("Run TreatmentShaderSpecializationTest before validating runtime variants")
    variant=program(emitted.read_text(),True)
    params={"finish_image":1,"sharpen_mode":2,"sharpness":.25,"quantize":1,"selective":1,
            "cleanup":.25 if mask&1 else 0.,"deband":.25 if mask&2 else 0.,
            "chroma":.25 if mask&4 else 0.,"antialias":.25 if mask&8 else 0.}
    for source_texture in (flat_tex,edge_tex):
        reference=draw(spatial,{"rgb_tex":source_texture},16,16,params)
        actual=draw(variant,{"rgb_tex":source_texture},16,16,params)
        verify(max(abs(a-b) for a,b in zip(reference,actual))<=1,f"Runtime spatial variant {mask} changed enabled-filter output")
    for kind in ("OES","YUV"):
        program(emitted.read_text(),True,kind);checks+=1
# Final dithering remains valid when an earlier pass did the debanding.
no_filters=program((root/"app/build/reports/treatment-shaders/spatial-0.glsl").read_text(),True)
params={"finish_image":1,"finish_dither":1,"quantize":1}
verify(draw(no_filters,{"rgb_tex":flat_tex},16,16,params)==draw(spatial,{"rgb_tex":flat_tex},16,16,params),"Finishing dither lost after specializing disabled filters")

# Temporal must reject a scene cut and preserve unchanged pixels.
white=texture([230,230,230,255]*256,16,16)
cut=draw(temporal,{"source":white,"history_tex":edge_tex},16,16,{"strength":.5})
verify(max(abs(cut[i]-230) for i in range(len(cut)) if i%4!=3)<=1,"Temporal scene cut rejection failed")
stable=draw(temporal,{"source":edge_tex,"history_tex":edge_tex},16,16,{"strength":.5})
verify(stable==pattern,"Temporal must preserve stable edges")

# Run the actual trained CNN in two floating feature targets and its application pass.
fa=texture(None,16,16,True);fb=texture(None,16,16,True)
draw(features[0],{"source":flat_tex},16,16,target=framebuffer(fa),read=False)
draw(features[1],{"source":flat_tex},16,16,target=framebuffer(fb),read=False)
out=draw(neural,{"source":flat_tex,"features_a":fa,"features_b":fb},16,16,{"strength":0.})
verify(out==flat,"Zero neural strength must be an exact bypass")
out=draw(neural,{"source":flat_tex,"features_a":fa,"features_b":fb},16,16,{"strength":1.})
verify(all(abs((out[i]-flat[i])-(out[i+1]-flat[i+1]))<=1 and abs((out[i]-flat[i])-(out[i+2]-flat[i+2]))<=1 for i in range(0,len(out),4)),"Neural luma correction must preserve chroma")
verify(max(abs(a-b) for a,b in zip(out,flat))<=21,"Neural residual must remain bounded")

# A CPU reference checks the trained weights and feature layout, not just shader compilation.
noise=[]
for y in range(16):
    for x in range(16):
        value=40+(x*37+y*19)%150;noise.extend([value,value+10,value+20,255])
noise_tex=texture(noise,16,16)
draw(features[0],{"source":noise_tex},16,16,target=framebuffer(fa),read=False)
draw(features[1],{"source":noise_tex},16,16,target=framebuffer(fb),read=False)
actual=draw(neural,{"source":noise_tex,"features_a":fa,"features_b":fb},16,16,{"strength":1.})
layers=json.loads((ASSETS/"restore.json").read_text())["layers"]
a,b,c=layers
def coordinate(value):return min(15,max(0,value))
def half(value):return struct.unpack('e',struct.pack('e',value))[0]
gray=[[sum(noise[(y*16+x)*4+k]*weight for k,weight in enumerate((.2126,.7152,.0722)))/255 for x in range(16)] for y in range(16)]
hidden=[[[max(0.,a['b'][channel]+sum(gray[coordinate(y+1-ky)][coordinate(x+kx-1)]*a['w'][ky][kx][0][channel] for ky in range(3) for kx in range(3))) for channel in range(8)] for x in range(16)] for y in range(16)]
middle=[[[half(max(0.,b['b'][channel]+sum(hidden[y][x][i]*b['w'][0][0][i][channel] for i in range(8)))) for channel in range(8)] for x in range(16)] for y in range(16)]
reference=[]
for y in range(16):
    for x in range(16):
        delta=c['b'][0]+sum(middle[coordinate(y+1-ky)][coordinate(x+kx-1)][i]*c['w'][ky][kx][i][0] for ky in range(3) for kx in range(3) for i in range(8))
        original=[v/255 for v in noise[(y*16+x)*4:(y*16+x)*4+3]]
        delta=min(1-max(original),max(-min(original),min(.08,max(-.08,delta))))
        reference.extend([round((value+delta)*255) for value in original]+[255])
verify(max(abs(a-b) for a,b in zip(actual,reference))<=2,"GPU CNN must match the actual trained CPU reference, including half-float features")
verify(any(abs(a-b)>1 for a,b in zip(actual,noise)),"Neural-on must perform a real residual reconstruction")

# Banding reduction must affect a smooth quantized gradient while maintaining its overall tone.
bands=[]
for y in range(16):
    for x in range(16):bands.extend([100+2*(x//4)]*3+[255])
band_tex=texture(bands,16,16)
clean=draw(spatial,{"rgb_tex":band_tex},16,16,{"finish_image":1,"selective":1,"quantize":1,"deband":1.})
verify(any(a!=b for a,b in zip(clean,bands)),"Debanding-on must process the quantized gradient")
verify(abs(sum(clean[::4])-sum(bands[::4]))/256<2,"Debanding must not change the overall scene brightness")

# The adaptive methods may change luminance, but must avoid colored sharpening fringes.
colored=[]
for y in range(16):
    for x in range(16):
        base=60+(x//4)*15;colored.extend([base,base+20,base+40,255])
colored_tex=texture(colored,16,16)
for mode in (1,2):
    out=draw(spatial,{"rgb_tex":colored_tex},16,16,{"finish_image":1,"sharpen_mode":mode,"sharpness":1.})
    verify(all(max(out[i+k]-colored[i+k] for k in range(3))-min(out[i+k]-colored[i+k] for k in range(3))<=1 for i in range(0,len(out),4)),"Adaptive sharpening must preserve chroma differences")

report=f"GLES2 treatment: {checks} shader/flat-color/HUD/transform/SGSR/temporal/half-float-CNN checks passed.\n"
output=root/"app/build/reports/dashboard/image-treatment-validation.txt";output.parent.mkdir(parents=True,exist_ok=True);output.write_text(report)
print(report,end="")
terminate(display)
