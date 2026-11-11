#!/usr/bin/env python3
"""Small GLES2 context and ctypes helpers for the optional treatment shader verification.

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
surface = create_surface(display, config, (integer * 5)(0x3057, 128, 0x3056, 128, 0x3038))
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


