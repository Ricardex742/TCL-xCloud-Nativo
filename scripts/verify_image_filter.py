#!/usr/bin/env python3
"""Compile the actual app fragment shader and verify RGB output in a headless Mesa GLES context.

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
context = create_context(display, config, None, (integer * 3)(0x3098, 2, 0x3038))
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


source = (root / "app/src/main/java/org/webrtc/ImageAdjustmentDrawer.java").read_text()
block = source.split("private static final String FRAGMENT_SHADER =", 1)[1].split("private static final class Adjustments", 1)[0]
fragment = "".join(json.loads(literal) for literal in re.findall(r'"(?:\\.|[^"\\])*"', block))
vertex = compile_source(0x8B31, "attribute vec2 pos; varying vec2 tc; void main(){ gl_Position=vec4(pos,0.,1.); tc=pos*.5+.5; }")
programs = {}
samplers = {
    "RGB": "uniform sampler2D rgb_tex; vec4 sample(vec2 p){return texture2D(rgb_tex,p);}",
    "OES": "uniform samplerExternalOES oes_tex; vec4 sample(vec2 p){return texture2D(oes_tex,p);}",
    "YUV": "uniform sampler2D y_tex; uniform sampler2D u_tex; uniform sampler2D v_tex; "
           "vec4 sample(vec2 p){float y=texture2D(y_tex,p).r; float u=texture2D(u_tex,p).r-.5; "
           "float v=texture2D(v_tex,p).r-.5; return vec4(y+1.4*v,y-.34*u-.71*v,y+1.77*u,1.);}",
}
for kind, sampler in samplers.items():
    prefix = "#extension GL_OES_EGL_image_external : require\n" if kind == "OES" else ""
    shader = compile_source(0x8B30, prefix + "precision mediump float; varying vec2 tc;\n" + sampler + fragment)
    program = create_program()
    attach_shader(program, vertex)
    attach_shader(program, shader)
    bind_attribute(program, 0, b"pos")
    link_program(program)
    status = integer()
    program_status(program, 0x8B82, C.byref(status))
    if not status.value:
        log = C.create_string_buffer(8192)
        program_log(program, len(log), None, log)
        raise AssertionError(log.value.decode())
    programs[kind] = program

program = programs["RGB"]
use_program(program)
uniform1i(uniform_location(program, b"rgb_tex"), 0)
uniform2f(uniform_location(program, b"step_x"), 1 / 8, 0)
uniform2f(uniform_location(program, b"step_y"), 0, 1 / 8)
viewport(0, 0, 8, 8)
texture = uint()
gen_textures(1, C.byref(texture))
bind_texture(0x0DE1, texture)
for key, value in [(0x2801, 0x2600), (0x2800, 0x2600), (0x2802, 0x812F), (0x2803, 0x812F)]:
    tex_parameter(0x0DE1, key, value)
vertices = (C.c_float * 8)(-1, -1, 1, -1, -1, 1, 1, 1)
vertex_pointer(0, 2, 0x1406, 0, 0, vertices)
enable_attribute(0)


def render(data, sharpness, saturation):
    pixels = (C.c_ubyte * len(data))(*data)
    tex_image(0x0DE1, 0, 0x1908, 8, 8, 0, 0x1908, 0x1401, pixels)
    uniform1f(uniform_location(program, b"sharpness"), sharpness)
    uniform1f(uniform_location(program, b"saturation"), saturation)
    draw_arrays(5, 0, 4)
    result = (C.c_ubyte * 256)()
    read_pixels(0, 0, 8, 8, 0x1908, 0x1401, result)
    return list(result)


flat = [200, 100, 50, 255] * 64
for sharpness, saturation, expected in [(0, 1, [200, 100, 50]), (1, 1, [200, 100, 50]),
                                       (0, 0, [124, 124, 124]), (0, 1.5, [238, 88, 13])]:
    actual = render(flat, sharpness, saturation)[4 * (4 * 8 + 4):][:3]
    assert all(abs(a - b) <= 2 for a, b in zip(actual, expected)), (sharpness, saturation, actual, expected)
edge = [102, 102, 102, 255] * 64
center = 4 * (4 * 8 + 4)
edge[center:center + 4] = [153, 153, 153, 255]
actual = render(edge, .5, 1)[center:center + 3]
assert all(abs(value - 179) <= 2 for value in actual), actual
report = f"{get_string(0x1F02).decode()}\nRGB/OES/YUV shaders compiled and linked.\n5 pixel-output checks passed.\n"
print(report, end="")
output = root / "app/build/reports/dashboard/image-filter-validation.txt"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(report)
make_current(display, None, None, None)
terminate(display)
