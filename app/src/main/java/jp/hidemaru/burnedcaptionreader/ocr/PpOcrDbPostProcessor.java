package jp.hidemaru.burnedcaptionreader.ocr;
import java.util.*;
/** Bounded pure-Java DB rectangles: threshold .3, mean box score .6, unclip1.5.
 * Uses external 8-connected contours and convex-hull minimum-area rectangles.
 * Unlike OpenCV RETR_LIST, inner holes are not emitted as independent text boxes.
 * Rectangle expansion is analytic; no OpenCV/Clipper runtime is bundled.
 */
public final class PpOcrDbPostProcessor {
 public static final int MAX_COMPONENTS=1000;
 public static final class Box {
  public final float[] points;public final double score;public final float left,top,right,bottom,angle;
  public Box(float[]p,double s){points=p.clone();score=s;float l=1,t=1,r=0,b=0;for(int i=0;i<8;i+=2){l=Math.min(l,p[i]);r=Math.max(r,p[i]);t=Math.min(t,p[i+1]);b=Math.max(b,p[i+1]);}left=l;right=r;top=t;bottom=b;angle=(float)Math.toDegrees(Math.atan2(p[3]-p[1],p[2]-p[0]));}
  public float width(){return right-left;}public float height(){return bottom-top;}
 }
 private static final class Point {final double x,y;Point(double x,double y){this.x=x;this.y=y;}}
 public static List<Box> decode(float[]map,int w,int h){
  if(w<1||h<1||(long)w*h!=map.length||map.length>1024*1024)throw new IllegalArgumentException("Invalid DB map");
  boolean[]seen=new boolean[map.length];int[]queue=new int[map.length];List<Box>out=new ArrayList<>();int components=0;
  for(int k=0;k<map.length;k++)if(!Float.isFinite(map[k])||map[k]<0||map[k]>1.001f)throw new IllegalArgumentException("Invalid DB probability");
  for(int seed=map.length-1;seed>=0&&components<MAX_COMPONENTS;seed--){if(seen[seed]||map[seed]<=.3f)continue;components++;int head=0,tail=0;queue[tail++]=seed;seen[seed]=true;List<Point>boundary=new ArrayList<>();
   while(head<tail){int p=queue[head++],x=p%w,y=p/w;boolean edge=false;
    for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){if(dx==0&&dy==0)continue;int xx=x+dx,yy=y+dy;if(xx<0||xx>=w||yy<0||yy>=h){edge=true;continue;}int q=yy*w+xx;if(map[q]<=.3f){edge=true;continue;}if(!seen[q]){seen[q]=true;queue[tail++]=q;}}
    if(edge)boundary.add(new Point(x,y));
   }
   if(boundary.size()<4)continue;List<Point>hull=hull(boundary);if(hull.size()<3)continue;double[]rect=minimumRectangle(hull);double rw=rect[4]-rect[2],rh=rect[5]-rect[3];if(Math.min(rw,rh)<3)continue;
   double score=score(map,w,h,rect);if(score<.6)continue;double d=rw*rh*1.5/(2*(rw+rh));rect[2]-=d;rect[3]-=d;rect[4]+=d;rect[5]+=d;if(Math.min(rw+2*d,rh+2*d)<5)continue;float[]points=points(rect,w,h);Box box=new Box(points,score);if(box.width()>0&&box.height()>0)out.add(box);
  }
  out.sort(Comparator.comparingDouble((Box b)->b.top).thenComparingDouble(b->b.left));return Collections.unmodifiableList(out);
 }
 private static double cross(Point a,Point b,Point c){return(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
 private static List<Point>hull(List<Point>p){p.sort(Comparator.comparingDouble((Point v)->v.x).thenComparingDouble(v->v.y));List<Point>h=new ArrayList<>();for(Point v:p){while(h.size()>1&&cross(h.get(h.size()-2),h.get(h.size()-1),v)<=0)h.remove(h.size()-1);h.add(v);}int lower=h.size();for(int i=p.size()-2;i>=0;i--){Point v=p.get(i);while(h.size()>lower&&cross(h.get(h.size()-2),h.get(h.size()-1),v)<=0)h.remove(h.size()-1);h.add(v);}if(h.size()>1)h.remove(h.size()-1);return h;}
 // Rectangle coordinates in an orthonormal basis: c,s,minU,minV,maxU,maxV.
 private static double[]minimumRectangle(List<Point>h){double area=Double.POSITIVE_INFINITY;double[]best=null;for(int i=0;i<h.size();i++){Point a=h.get(i),b=h.get((i+1)%h.size());double dx=b.x-a.x,dy=b.y-a.y,n=Math.hypot(dx,dy),c=dx/n,s=dy/n,minU=Double.POSITIVE_INFINITY,minV=minU,maxU=-minU,maxV=-minU;for(Point p:h){double u=p.x*c+p.y*s,v=-p.x*s+p.y*c;minU=Math.min(minU,u);maxU=Math.max(maxU,u);minV=Math.min(minV,v);maxV=Math.max(maxV,v);}double ar=(maxU-minU)*(maxV-minV);if(ar<area){area=ar;best=new double[]{c,s,minU,minV,maxU,maxV};}}return best;}
 private static double score(float[]m,int w,int h,double[]r){double total=0;int n=0;float[]p=points(r,w,h);int l=w-1,t=h-1,rr=0,b=0;for(int i=0;i<8;i+=2){l=Math.min(l,(int)Math.floor(p[i]*w));rr=Math.max(rr,(int)Math.ceil(p[i]*w));t=Math.min(t,(int)Math.floor(p[i+1]*h));b=Math.max(b,(int)Math.ceil(p[i+1]*h));}for(int y=Math.max(0,t);y<=Math.min(h-1,b);y++)for(int x=Math.max(0,l);x<=Math.min(w-1,rr);x++){double u=x*r[0]+y*r[1],v=-x*r[1]+y*r[0];if(u>=r[2]-.5&&u<=r[4]+.5&&v>=r[3]-.5&&v<=r[5]+.5){total+=m[y*w+x];n++;}}return n==0?0:total/n;}
 private static float[]points(double[]r,int w,int h){double[][]p=new double[4][2];double[]us={r[2],r[4],r[4],r[2]},vs={r[3],r[3],r[5],r[5]};for(int i=0;i<4;i++){p[i][0]=us[i]*r[0]-vs[i]*r[1];p[i][1]=us[i]*r[1]+vs[i]*r[0];}Arrays.sort(p,Comparator.comparingDouble(v->v[0]));double[]tl=p[0][1]<p[1][1]?p[0]:p[1],bl=tl==p[0]?p[1]:p[0],tr=p[2][1]<p[3][1]?p[2]:p[3],br=tr==p[2]?p[3]:p[2];float[]out=new float[8];double[][]order={tl,tr,br,bl};for(int i=0;i<4;i++){out[i*2]=(float)Math.max(0,Math.min(1,order[i][0]/w));out[i*2+1]=(float)Math.max(0,Math.min(1,order[i][1]/h));}return out;}
}
