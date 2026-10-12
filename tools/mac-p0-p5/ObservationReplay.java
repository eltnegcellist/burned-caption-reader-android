import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.lang.reflect.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import jp.hidemaru.burnedcaptionreader.ocr.*;
import jp.hidemaru.burnedcaptionreader.subtitle.*;

/** Desktop replay of unchanged production Java. No OCR, Android, or audible TTS. */
public class ObservationReplay {
  static String enc(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
  static String dec(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
  static class Frame {
    String id, image; long ms; boolean rawEmpty, strip;
    List<OcrLine> raw = new ArrayList<>(), detection = new ArrayList<>();
  }
  static OcrLine row(String[] a, int offset, List<OcrLine> parts) {
    return new OcrLine(Integer.parseInt(a[offset]),dec(a[offset+1]),Double.parseDouble(a[offset+2]),
        Float.parseFloat(a[offset+3]),Float.parseFloat(a[offset+4]),Float.parseFloat(a[offset+5]),Float.parseFloat(a[offset+6]),
        parts,Float.parseFloat(a[offset+7]),Double.parseDouble(a[offset+8]));
  }
  static List<Frame> read(Path p) throws Exception {
    List<Frame> out = new ArrayList<>(); Frame f=null;
    for(String s:Files.readAllLines(p)) {
      String[] a=s.split("\t",-1);
      if(a[0].equals("F")) {
        f=new Frame(); f.id=a[1];f.ms=Long.parseLong(a[2]);f.image=a[3];
        f.rawEmpty=Boolean.parseBoolean(a[4]); f.strip=Boolean.parseBoolean(a[5]);out.add(f);
      } else {
        List<OcrLine> parts=new ArrayList<>();
        if(a.length>10 && !a[10].isEmpty()) for(String x:a[10].split(";")) parts.add(row(x.split(",",-1),0,List.of()));
        (a[0].equals("R")?f.raw:f.detection).add(row(a,1,parts));
      }
    }
    return out;
  }
  static String text(List<OcrLine> rows) {return String.join("\n",rows.stream().map(OcrLine::getText).toList());}
  static String selected(List<AutoSubtitleRegionTracker.Selection> rows) {
    return String.join("\n",rows.stream().map(AutoSubtitleRegionTracker.Selection::getText).toList());
  }
  static String signature(List<AutoSubtitleRegionTracker.Selection> rows) {
    return String.join("|",rows.stream().map(s->s.getTrackId()+":"+s.getTop()+":"+s.getBottom()+":"+s.isLocked()+":"+s.getText()).toList());
  }
  static Object field(Object o,String name)throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
  static boolean predicate(AutoSubtitleRegionTracker t,Object candidate,String name)throws Exception {
    var m=AutoSubtitleRegionTracker.class.getDeclaredMethod(name,candidate.getClass());m.setAccessible(true);return (boolean)m.invoke(t,candidate);
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    List<Frame> frames=read(Path.of(args[0])); boolean shadow=Boolean.parseBoolean(args[1]);
    Class<?> traceClass=null, composer=null, shadowClass=null;
    try {traceClass=Class.forName("jp.hidemaru.burnedcaptionreader.subtitle.AutoSubtitleRegionTracker$DecisionTrace");}
    catch(ClassNotFoundException ignored){}
    try {composer=Class.forName("jp.hidemaru.burnedcaptionreader.subtitle.OcrVisualLineComposer");}
    catch(ClassNotFoundException ignored){}
    if(shadow) shadowClass=Class.forName("jp.hidemaru.burnedcaptionreader.subtitle.CaptionFrameShadow");
    Object sh=shadow?shadowClass.getConstructor().newInstance():null;
    // One full prewarm pass; timings exclude image decode, serialization, tracing and cold JVM.
    AutoSubtitleRegionTracker warm=new AutoSubtitleRegionTracker();
    for(Frame f:frames) warm.selectForRecognition(f.ms,new OcrResult(text(f.detection),80,f.detection));
    AutoSubtitleRegionTracker tracker=new AutoSubtitleRegionTracker(), traced=new AutoSubtitleRegionTracker();
    for(Frame f:frames) {
      BufferedImage image=shadow?ImageIO.read(Path.of(f.image).toFile()):null;
      Object pixels=null, prediction=null,rescue=null; long probeNs=0,rescueNs=0;
      if(shadow) {
        Class<?> pixelClass=Class.forName("jp.hidemaru.burnedcaptionreader.subtitle.CaptionFrameShadow$PixelSource");
        BufferedImage bitmap=image;
        pixels=Proxy.newProxyInstance(pixelClass.getClassLoader(),new Class<?>[]{pixelClass},(p,m,a)->bitmap.getRGB((int)a[0],(int)a[1]));
        long started=System.nanoTime();
        prediction=shadowClass.getMethod("probe",pixelClass,int.class,int.class,int.class,long.class)
            .invoke(sh,pixels,image.getWidth(),image.getHeight(),1,f.ms);
        probeNs=System.nanoTime()-started;
        started=System.nanoTime();
        rescue=shadowClass.getMethod("inspectMissing",pixelClass,int.class,int.class,long.class,boolean.class,boolean.class)
            .invoke(sh,pixels,image.getWidth(),image.getHeight(),f.ms,f.rawEmpty,f.strip);
        rescueNs=System.nanoTime()-started;
      }
      OcrResult input=new OcrResult(text(f.detection),80,f.detection);
      long started=System.nanoTime();
      List<AutoSubtitleRegionTracker.Selection> bands=tracker.selectForRecognition(f.ms,input);
      long selectionNs=System.nanoTime()-started;
      List<String> trace=new ArrayList<>(); boolean parity=true;
      if(traceClass!=null) {
        Object sink=Proxy.newProxyInstance(traceClass.getClassLoader(),new Class<?>[]{traceClass},(p,m,a)-> {
          trace.add("T\t"+f.id+"\t"+a[0]+"\t"+a[1]+"\t"+enc((String)a[2])+"\t"+a[3]+"\t"+a[4]);return null;
        });
        List<AutoSubtitleRegionTracker.Selection> other=(List<AutoSubtitleRegionTracker.Selection>)
          AutoSubtitleRegionTracker.class.getMethod("selectForRecognition",long.class,OcrResult.class,traceClass).invoke(traced,f.ms,input,sink);
        parity=signature(bands).equals(signature(other));
      }
      List<OcrLine> old=new ArrayList<>(f.raw);
      old.sort(Comparator.comparingDouble(OcrLine::getTop).thenComparingDouble(OcrLine::getLeft));
      List<OcrLine> composed=old;long composeNs=0;
      if(composer!=null) {
        started=System.nanoTime();composed=(List<OcrLine>)composer.getMethod("compose",List.class).invoke(null,f.raw);
        composeNs=System.nanoTime()-started;
      }
      System.out.println("F\t"+f.id+"\t"+f.ms+"\t"+enc(selected(bands))+"\t"+selectionNs/1000.0+"\t"+parity+"\t"+enc(text(old))+"\t"+enc(text(composed))+"\t"+composeNs/1000.0+"\t"+old.size()+"\t"+composed.size());
      for(String t:trace)System.out.println(t);
      for(Object lane:(List<?>)field(tracker,"lanes")) {
        Object candidate=field(lane,"current");if(candidate==null)continue;
        boolean strong=predicate(tracker,candidate,"isStrongCaptionShape"),upper=predicate(tracker,candidate,"isUpperCaptionRescueShape"),early=predicate(tracker,candidate,"isEarlyRecognitionShape");
        System.out.println("L\t"+f.id+"\t"+enc((String)field(candidate,"text"))+"\t"+strong+"\t"+upper+"\t"+early+"\t"+field(lane,"observations")+"\t"+field(lane,"transitions")+"\t"+(f.ms-(long)field(lane,"firstSeenAt")));
      }
      for(var b:bands)System.out.println("S\t"+f.id+"\t"+b.getTrackId()+"\t"+b.getTop()+"\t"+b.getBottom()+"\t"+b.isLocked()+"\t"+enc(b.getText())+"\t"+b.getConfidence());
      if(shadow) {
        Class<?> pc=prediction.getClass(),rc=rescue.getClass();
        System.out.println("H\t"+f.id+"\t"+pc.getField("wouldSkip").get(prediction)+"\t"+pc.getField("reason").get(prediction)+"\t"+probeNs/1000.0+"\t"+rc.getField("wouldRescue").get(rescue)+"\t"+rc.getField("reason").get(rescue)+"\t"+rescueNs/1000.0);
        if(bands.size()==1 && bands.get(0).isLocked()) {
          var b=bands.get(0);shadowClass.getMethod("trust",int.class,float.class,float.class,long.class).invoke(sh,b.getTrackId(),b.getTop(),b.getBottom(),f.ms);
        } else shadowClass.getMethod("clearTrust").invoke(sh);
      }
    }
  }
}
