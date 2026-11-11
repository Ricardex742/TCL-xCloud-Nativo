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
