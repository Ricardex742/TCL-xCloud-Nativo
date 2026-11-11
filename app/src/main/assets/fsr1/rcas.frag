// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// MIT license: LICENSE.txt. FSR 1.0.2 RCAS with FSR_RCAS_DENOISE, GLES 2 highp port.
#ifdef FSR_SOURCE_OES
#extension GL_OES_EGL_image_external : require
#endif
precision highp float;
#ifdef FSR_SOURCE_OES
uniform samplerExternalOES source_tex;
#else
uniform sampler2D source_tex;
#endif
#ifdef FSR_DIRECT_INPUT
uniform vec2 source_origin;
uniform vec2 source_step_x;
uniform vec2 source_step_y;
#endif
uniform vec2 input_size;
uniform vec2 viewport_origin;
uniform float rcas_strength;
uniform float saturation;
uniform int sharpening_mode;
uniform float adaptive_amount;
uniform bool finish_dither;
uniform float brightness;
uniform float contrast_adjust;
vec3 loadPixel(vec2 p) {
    p=clamp(p,vec2(0.0),input_size-1.0);
#ifdef FSR_DIRECT_INPUT
    vec2 uv=source_origin+p.x*source_step_x+p.y*source_step_y;
#else
    vec2 uv=(p+0.5)/input_size;
#endif
    return clamp(texture2D(source_tex,uv).rgb,0.0,1.0);
}
float luma(vec3 c) { return c.b*0.5+(c.r*0.5+c.g); }
// Luma-only finishing with coring and a local overshoot bound. Original project code (MIT).
float Y(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
vec3 adaptiveSharpen(vec3 e,vec3 w,vec3 r,vec3 n,vec3 s,int mode,float amount){
 if(amount<=0.)return e;
 if(mode==0)return clamp(e+(e-(w+r+n+s)*.25)*amount,0.,1.);
 float y=Y(e),yn=Y(n),ys=Y(s),yw=Y(w),yr=Y(r);
 float lo=min(y,min(min(yn,ys),min(yw,yr))),hi=max(y,max(max(yn,ys),max(yw,yr)));
 float range=hi-lo,delta;
 if(mode==2){float d=y-(yn+ys+yw+yr)*.25;delta=sign(d)*max(abs(d)-4./255.,0.)*amount*2.;}
 else{
  float noise=1.-.5*clamp(abs((yn+ys+yw+yr)*.25-y)/max(range,1.e-6),0.,1.);
  vec3 mn=min(min(n,s),min(w,r)),mx=max(max(n,s),max(w,r));
  vec3 hitMin=min(mn,e)/max(4.*mx,vec3(1.e-6));
  vec3 hitMax=(1.-max(mx,e))/min(4.*mn-4.,vec3(-1.e-6));
  vec3 hit=max(-hitMin,hitMax);
  float lobe=max(-.1875,min(max(max(hit.r,hit.g),hit.b),0.))*pow(2.,-2.*(1.-amount))*noise;
  delta=Y((lobe*(n+s+w+r)+e)/(4.*lobe+1.))-y;
 }
 delta*=smoothstep(4./255.,8./255.,range);
 float margin=(2.+4.*amount)/255.;
 delta=clamp(y+delta,lo-margin,hi+margin)-y;
 // Equal channel changes keep chroma intact, including at saturated colors.
 delta=clamp(delta,-min(min(e.r,e.g),e.b),1.-max(max(e.r,e.g),e.b));
 return e+vec3(delta);
}

void main() {
    vec2 p=floor(gl_FragCoord.xy-viewport_origin);
    vec3 e=loadPixel(p);
    vec3 color=e;
    if(rcas_strength>0.0) {
        vec3 b=loadPixel(p+vec2(0.0,-1.0));
        vec3 d=loadPixel(p+vec2(-1.0,0.0));
        vec3 f=loadPixel(p+vec2(1.0,0.0));
        vec3 h=loadPixel(p+vec2(0.0,1.0));
        if(sharpening_mode>0) {
            color=adaptiveSharpen(e,d,f,h,b,sharpening_mode,adaptive_amount);
        } else {
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
    }
    float luminance=dot(color,vec3(0.299,0.587,0.114));
    color=mix(vec3(luminance),color,saturation);
    if(brightness!=0.0 || contrast_adjust!=0.0) color=(color-0.5)*(1.0+contrast_adjust)+0.5+brightness;
    if(finish_dither)color+=(fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))))-.5)/255.;
    gl_FragColor=vec4(clamp(color,0.0,1.0),1.0);
}
