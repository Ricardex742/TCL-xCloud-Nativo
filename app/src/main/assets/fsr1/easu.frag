// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// MIT license: LICENSE.txt. FSR 1.0.2 EASU, adapted to GLES 2 highp fragment shaders.
// Twelve RGB point loads replace per-channel textureGather. Exact reciprocals replace
// ffx_a approximations; epsilon guards make uniform black/white neighborhoods finite.
precision highp float;
uniform sampler2D source_tex;
uniform vec2 input_size;
uniform vec2 output_size;
vec3 loadPixel(vec2 p) {
    return texture2D(source_tex,(clamp(p,vec2(0.0),input_size-1.0)+0.5)/input_size).rgb;
}
float luma(vec3 c) { return c.b*0.5+(c.r*0.5+c.g); }
void edge(inout vec2 dir,inout float len,float w,float a,float b,float c,float d,float e) {
    float dx=d-b;
    float dy=e-a;
    dir+=vec2(dx,dy)*w;
    float lx=clamp(abs(dx)/max(max(abs(d-c),abs(c-b)),1.0e-6),0.0,1.0);
    float ly=clamp(abs(dy)/max(max(abs(e-c),abs(c-a)),1.0e-6),0.0,1.0);
    len+=(lx*lx+ly*ly)*w;
}
void tap(inout vec3 color,inout float weight,vec2 off,vec2 dir,vec2 len,float lob,float clp,vec3 c) {
    vec2 v=vec2(dot(off,dir),dot(off,vec2(-dir.y,dir.x)))*len;
    float d2=min(dot(v,v),clp);
    float wb=(2.0/5.0)*d2-1.0;
    float wa=lob*d2-1.0;
    wb=(25.0/16.0)*wb*wb-(25.0/16.0-1.0);
    float w=wb*wa*wa;
    color+=c*w;weight+=w;
}
void main() {
    vec2 pp=gl_FragCoord.xy*input_size/output_size-0.5;
    vec2 fp=floor(pp);pp-=fp;
    vec3 b=loadPixel(fp+vec2(0.0,-1.0));
    vec3 c=loadPixel(fp+vec2(1.0,-1.0));
    vec3 e=loadPixel(fp+vec2(-1.0,0.0));
    vec3 f=loadPixel(fp);
    vec3 g=loadPixel(fp+vec2(1.0,0.0));
    vec3 h=loadPixel(fp+vec2(2.0,0.0));
    vec3 i=loadPixel(fp+vec2(-1.0,1.0));
    vec3 j=loadPixel(fp+vec2(0.0,1.0));
    vec3 k=loadPixel(fp+vec2(1.0,1.0));
    vec3 l=loadPixel(fp+vec2(2.0,1.0));
    vec3 n=loadPixel(fp+vec2(0.0,2.0));
    vec3 o=loadPixel(fp+vec2(1.0,2.0));
    vec2 dir=vec2(0.0);float len=0.0;
    edge(dir,len,(1.0-pp.x)*(1.0-pp.y),luma(b),luma(e),luma(f),luma(g),luma(j));
    edge(dir,len,pp.x*(1.0-pp.y),luma(c),luma(f),luma(g),luma(h),luma(k));
    edge(dir,len,(1.0-pp.x)*pp.y,luma(f),luma(i),luma(j),luma(k),luma(n));
    edge(dir,len,pp.x*pp.y,luma(g),luma(j),luma(k),luma(l),luma(o));
    float dir2=dot(dir,dir);
    dir=dir2<(1.0/32768.0)?vec2(1.0,0.0):dir*inversesqrt(dir2);
    len*=0.5;len*=len;
    float stretch=dot(dir,dir)/max(max(abs(dir.x),abs(dir.y)),1.0e-6);
    vec2 lengths=vec2(1.0+(stretch-1.0)*len,1.0-0.5*len);
    float lob=0.5+((1.0/4.0-0.04)-0.5)*len;
    float clp=1.0/lob;
    vec3 minimum=min(min(f,g),min(j,k));
    vec3 maximum=max(max(f,g),max(j,k));
    vec3 color=vec3(0.0);float weight=0.0;
    tap(color,weight,vec2(0.0,-1.0)-pp,dir,lengths,lob,clp,b);
    tap(color,weight,vec2(1.0,-1.0)-pp,dir,lengths,lob,clp,c);
    tap(color,weight,vec2(-1.0,1.0)-pp,dir,lengths,lob,clp,i);
    tap(color,weight,vec2(0.0,1.0)-pp,dir,lengths,lob,clp,j);
    tap(color,weight,vec2(0.0,0.0)-pp,dir,lengths,lob,clp,f);
    tap(color,weight,vec2(-1.0,0.0)-pp,dir,lengths,lob,clp,e);
    tap(color,weight,vec2(1.0,1.0)-pp,dir,lengths,lob,clp,k);
    tap(color,weight,vec2(2.0,1.0)-pp,dir,lengths,lob,clp,l);
    tap(color,weight,vec2(2.0,0.0)-pp,dir,lengths,lob,clp,h);
    tap(color,weight,vec2(1.0,0.0)-pp,dir,lengths,lob,clp,g);
    tap(color,weight,vec2(1.0,2.0)-pp,dir,lengths,lob,clp,o);
    tap(color,weight,vec2(0.0,2.0)-pp,dir,lengths,lob,clp,n);
    gl_FragColor=vec4(clamp(color/max(weight,1.0e-6),minimum,maximum),1.0);
}
