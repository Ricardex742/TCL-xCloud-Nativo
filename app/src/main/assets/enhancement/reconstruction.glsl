// xCloudNative spatial reconstruction, MIT. Not an implementation of DLSS or temporal FSR.
// One input pass: conservative luma restoration, directional detail and optional cubic sampling.
// RECON_LEVEL is specialized before shader compilation: 1=5 taps, 2=9, 3=13 (+13 for enlargement).
uniform vec2 step_x,step_y,source_size,output_size,viewport_origin;
uniform vec2 bounds_min,bounds_max;
uniform float reconstruction_strength,saturation,brightness,contrast_adjust;
uniform bool finish_dither;
float luminance(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
vec3 readAt(vec2 p){return sample(clamp(p,bounds_min,bounds_max)).rgb;}
vec3 offsetAt(vec2 d){return readAt(tc+d.x*step_x+d.y*step_y);}
// Catmull-Rom expressed as 9 bilinear fetches. Middle positive weights can share a fetch.
// The decoded texture transform is retained; offsets are in the logical source pixel axes.
#if RECON_LEVEL == 3
vec3 cubicSample(){
 vec2 grid=(gl_FragCoord.xy-viewport_origin)/output_size*source_size-.5;
 vec2 f=fract(grid),f2=f*f,f3=f2*f;
 vec2 w0=-.5*f+f2-.5*f3,w1=1.-2.5*f2+1.5*f3;
 vec2 w2=.5*f+2.*f2-1.5*f3,w3=-.5*f2+.5*f3;
 vec2 middle=w1+w2,mid=w2/middle;
 vec2 base=tc-f.x*step_x-f.y*step_y;
 vec3 color=vec3(0.);
 for(int row=0;row<3;row++){
  float dy=row==0?-1.:row==1?mid.y:2.;
  float wy=row==0?w0.y:row==1?middle.y:w3.y;
  for(int col=0;col<3;col++){
   float dx=col==0?-1.:col==1?mid.x:2.;
   float wx=col==0?w0.x:col==1?middle.x:w3.x;
   color+=readAt(base+dx*step_x+dy*step_y)*wx*wy;
  }
 }
 // Cubic lobes must not introduce extrema or colored fringes around subtitles/HUD.
 vec3 a=readAt(base),b=readAt(base+step_x),c=readAt(base+step_y),d=readAt(base+step_x+step_y);
 return clamp(color,min(min(a,b),min(c,d)),max(max(a,b),max(c,d)));
}
#endif
void main(){
 vec3 original=readAt(tc),result=original;
 if(reconstruction_strength>0.){
  vec3 west=offsetAt(vec2(-1,0)),east=offsetAt(vec2(1,0));
  vec3 north=offsetAt(vec2(0,1)),south=offsetAt(vec2(0,-1));
  float y=luminance(original),w=luminance(west),e=luminance(east),n=luminance(north),s=luminance(south);
  float lo=min(y,min(min(w,e),min(n,s))),hi=max(y,max(max(w,e),max(n,s)));
  float range=hi-lo,gradient=abs(e-w)+abs(n-s);
  float activity=abs(e-y)+abs(w-y)+abs(n-y)+abs(s-y);
  float coherence=clamp(gradient/max(activity,1.e-6),0.,1.);
  float strongest=max(max(abs(e-y),abs(w-y)),max(abs(n-y),abs(s-y)));
  float nearer=abs(e-w)>abs(n-s)?min(abs(e-y),abs(w-y)):min(abs(n-y),abs(s-y));
  // A clean step needs no deblurring. Also reject small encoder ripples beside that step.
  float sharpBoundary=smoothstep(.10,.25,strongest)*(1.-smoothstep(.01,.05,nearer));
  float crossMean=(w+e+n+s)*.25;
  float base=y,fine=y-crossMean,wide=0.;
  // Small uncorrelated differences are likely quantization noise. Large texture is preserved.
  float clean=(1.-smoothstep(.025,.07,range))*(1.-coherence)*.25;
  base=mix(y,crossMean,clean*reconstruction_strength);
  #if RECON_LEVEL >= 2
   float nw=luminance(offsetAt(vec2(-1,1))),ne=luminance(offsetAt(vec2(1,1)));
   float sw=luminance(offsetAt(vec2(-1,-1))),se=luminance(offsetAt(vec2(1,-1)));
   // Gaussian detail and normal-direction detail share the same samples.
   float gaussian=(4.*y+2.*(w+e+n+s)+nw+ne+sw+se)/16.;
   float normal=mix((n+s)*.5,(w+e)*.5,abs(e-w)/max(gradient,1.e-6));
   fine=mix(y-gaussian,y-normal,.35*coherence);
  #endif
  #if RECON_LEVEL == 3
   float w2=luminance(offsetAt(vec2(-2,0))),e2=luminance(offsetAt(vec2(2,0)));
   float n2=luminance(offsetAt(vec2(0,2))),s2=luminance(offsetAt(vec2(0,-2)));
   wide=(y-(w2+e2+n2+s2)*.25)-fine;
   // Avoid sharpening a continuous ramp or encoder ringing in a flat region.
   wide=sign(wide)*max(abs(wide)-2./255.,0.)*.20*coherence;
   if(output_size.x>source_size.x*1.01 || output_size.y>source_size.y*1.01){
    result=mix(original,cubicSample(),reconstruction_strength);
   }
  #endif
  // Do not classify coherent codec block steps as genuine fine detail.
  float floorNoise=2.5/255.;
  fine=sign(fine)*max(abs(fine)-floorNoise,0.);
  float gain=smoothstep(.008,.035,range)*(1.5+.5*coherence);
  float target=base+(fine*gain+wide)*(1.-sharpBoundary)*reconstruction_strength;
  target=clamp(target,lo,hi); // No new local extrema, including at 1-pixel text.
  // Cubic reconstruction and detail share one bound; do not stack their overshoot.
  float reconstructedY=luminance(result);
  float combined=clamp(reconstructedY+(target-y),lo,hi);
  float delta=clamp(combined-reconstructedY,-.08,.08);
  delta=clamp(delta,-min(min(result.r,result.g),result.b),1.-max(max(result.r,result.g),result.b));
  result+=vec3(delta); // Luma change keeps chroma differences unchanged.
 }
 float y=dot(result,vec3(.299,.587,.114));result=mix(vec3(y),result,saturation);
 result=(result-.5)*(1.+contrast_adjust)+.5+brightness;
 if(finish_dither)result+=(fract(52.9829189*fract(dot(gl_FragCoord.xy,vec2(.06711056,.00583715))))-.5)/255.;
 gl_FragColor=vec4(clamp(result,0.,1.),1.);
}
