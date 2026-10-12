import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import jp.hidemaru.burnedcaptionreader.subtitle.CaptionFrameShadow;

/** Tests independently specified pixel transitions, including unsampled glyphs. */
public class ShadowControls {
  public static void main(String[] args)throws Exception {
    for(String line:Files.readAllLines(Path.of(args[0]))) {
      String[] a=line.split("\t");var before=ImageIO.read(Path.of(a[1]).toFile());var after=ImageIO.read(Path.of(a[2]).toFile());
      CaptionFrameShadow s=new CaptionFrameShadow();boolean trusted=Boolean.parseBoolean(a[4]);
      if(trusted)s.trust(1,Float.parseFloat(a[5]),Float.parseFloat(a[6]),0);
      s.probe(before::getRGB,before.getWidth(),before.getHeight(),1,0);
      long start=System.nanoTime();var result=s.probe(after::getRGB,after.getWidth(),after.getHeight(),Integer.parseInt(a[7]),480);
      System.out.println(a[0]+"\t"+a[3]+"\t"+result.wouldSkip+"\t"+result.reason+"\t"+(System.nanoTime()-start)/1000.0+"\t"+result.bandPeakChannelDelta+"\t"+result.bandMeanDelta);
    }
  }
}
