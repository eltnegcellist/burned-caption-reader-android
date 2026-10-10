package jp.hidemaru.burnedcaptionreader.ocr;
/** BGR ImageNet normalization; video long-side640 and dimension divisor128. */
public final class PpOcrDetectionInput {
 public static final int LONG_SIDE=640;
 public final int width,height; public final float[] values;
 private PpOcrDetectionInput(int w,int h,float[]v){width=w;height=h;values=v;}
 public static PpOcrDetectionInput fromArgb(int[]p,int w,int h){
  if(w<1||h<1||(long)w*h!=p.length)throw new IllegalArgumentException("Invalid detector pixels");
  double ratio=LONG_SIDE/(double)Math.max(w,h);int dw=((int)(w*ratio)+127)/128*128,dh=((int)(h*ratio)+127)/128*128;dw=Math.max(128,dw);dh=Math.max(128,dh);int plane=dw*dh;float[]v=new float[3*plane];float[]mean={.485f,.456f,.406f},std={.229f,.224f,.225f};
  for(int y=0;y<dh;y++){double sy=Math.max(0,Math.min(h-1,(y+.5)*h/dh-.5));int y0=(int)sy,y1=Math.min(h-1,y0+1);double fy=sy-y0;
   for(int x=0;x<dw;x++){double sx=Math.max(0,Math.min(w-1,(x+.5)*w/dw-.5));int x0=(int)sx,x1=Math.min(w-1,x0+1);double fx=sx-x0;
    for(int c=0;c<3;c++){int shift=8*c;double a=((p[y0*w+x0]>>shift)&255)*(1-fx)+((p[y0*w+x1]>>shift)&255)*fx,b=((p[y1*w+x0]>>shift)&255)*(1-fx)+((p[y1*w+x1]>>shift)&255)*fx;v[c*plane+y*dw+x]=(Math.round(a*(1-fy)+b*fy)/255f-mean[c])/std[c];}
   }
  }return new PpOcrDetectionInput(dw,dh,v);
 }
}
