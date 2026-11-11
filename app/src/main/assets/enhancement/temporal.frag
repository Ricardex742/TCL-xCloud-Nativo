// Original project code (MIT). Local motion search, history clipping and scene-change rejection.
precision highp float;
varying vec2 tc;
uniform sampler2D source,history_tex;
uniform vec2 frame_size;
uniform float strength;
float Y(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
vec3 previous(vec2 p){return texture2D(history_tex,clamp(p,vec2(.5)/frame_size,1.-vec2(.5)/frame_size)).rgb;}
void main(){
 vec2 dx=vec2(1./frame_size.x,0.),dy=vec2(0.,1./frame_size.y);
 vec3 c=texture2D(source,tc).rgb,r=texture2D(source,clamp(tc+dx,0.,1.)).rgb;
 vec3 n=texture2D(source,clamp(tc+dy,0.,1.)).rgb;
 vec3 w=texture2D(source,clamp(tc-dx,0.,1.)).rgb,s=texture2D(source,clamp(tc-dy,0.,1.)).rgb;
 vec3 lo=min(c,min(min(r,n),min(w,s))),hi=max(c,max(max(r,n),max(w,s)));
 float best=100.;vec3 old=c;
 for(int i=0;i<5;i++){
  vec2 d=i==0?vec2(0.):i==1?dx:i==2?-dx:i==3?dy:-dy;
  vec3 h=previous(tc+d);
  float cost=abs(Y(h)-Y(c))+.35*(abs(Y(previous(tc+d+dx))-Y(r))+abs(Y(previous(tc+d+dy))-Y(n)));
  if(cost<best){best=cost;old=h;}
 }
 vec3 change=abs(old-c);float colorChange=max(max(change.r,change.g),change.b);
 float confidence=(1.-smoothstep(.006,.035,best))*(1.-smoothstep(.025,.075,colorChange));
 float edge=Y(hi)-Y(lo);
 float weight=min(.35,strength)*confidence*(1.-smoothstep(.10,.30,edge));
 gl_FragColor=vec4(mix(c,clamp(old,lo,hi),weight),1.);
}
