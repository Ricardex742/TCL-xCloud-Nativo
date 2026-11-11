// AMD FSR 1.0.2 adaptation; see LICENSE.txt and third_party/amd-fsr1/SOURCE.md.
attribute vec2 in_pos;
void main() { gl_Position=vec4(in_pos,0.0,1.0); }
