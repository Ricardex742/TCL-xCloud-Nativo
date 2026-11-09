// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// MIT license: LICENSE.txt. FSR 1.0.2 RCAS with FSR_RCAS_DENOISE, GLES 2 highp port.
precision highp float;
uniform sampler2D source_tex;
uniform vec2 input_size;
uniform vec2 viewport_origin;
uniform float rcas_strength;
uniform float saturation;
vec3 loadPixel(vec2 p) {
    return texture2D(source_tex,(clamp(p,vec2(0.0),input_size-1.0)+0.5)/input_size).rgb;
}
float luma(vec3 c) { return c.b*0.5+(c.r*0.5+c.g); }
void main() {
    vec2 p=floor(gl_FragCoord.xy-viewport_origin);
    vec3 e=loadPixel(p);
    vec3 color=e;
    if(rcas_strength>0.0) {
        vec3 b=loadPixel(p+vec2(0.0,-1.0));
        vec3 d=loadPixel(p+vec2(-1.0,0.0));
        vec3 f=loadPixel(p+vec2(1.0,0.0));
        vec3 h=loadPixel(p+vec2(0.0,1.0));
        float bl=luma(b),dl=luma(d),el=luma(e),fl=luma(f),hl=luma(h);
        float range=max(max(max(bl,dl),max(el,fl)),hl)-min(min(min(bl,dl),min(el,fl)),hl);
        float noise=clamp(abs(0.25*(bl+dl+fl+hl)-el)/max(range,1.0e-6),0.0,1.0);
        noise=1.0-0.5*noise;
        vec3 mn=min(min(b,d),min(f,h));
        vec3 mx=max(max(b,d),max(f,h));
        vec3 hitMin=min(mn,e)/max(4.0*mx,vec3(1.0e-6));
        vec3 denom=4.0*mn-4.0;
        denom=mix(vec3(-1.0),vec3(1.0),step(vec3(0.0),denom))*max(abs(denom),vec3(1.0e-6));
        vec3 hitMax=(1.0-max(mx,e))/denom;
        vec3 lobes=max(-hitMin,hitMax);
        float lobe=max(-0.1875,min(max(max(lobes.r,lobes.g),lobes.b),0.0))*rcas_strength*noise;
        color=(lobe*(b+d+f+h)+e)/(4.0*lobe+1.0);
    }
    float luminance=dot(color,vec3(0.299,0.587,0.114));
    gl_FragColor=vec4(clamp(mix(vec3(luminance),color,saturation),0.0,1.0),1.0);
}
