package jp.hidemaru.burnedcaptionreader.ocr;
import android.content.Context;import android.content.res.AssetFileDescriptor;import android.graphics.Bitmap;
import ai.onnxruntime.*;import java.io.*;import java.nio.*;import java.nio.channels.FileChannel;import java.security.MessageDigest;import java.util.*;
/** Bundled PP-OCRv5 mobile_det. Session and mapped model owned by one OCR worker. */
public final class PpOcrTextDetector implements AutoCloseable {
 public static final String MODEL_SHA256="a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d";
 private final OrtEnvironment environment;private final OrtSession session;private ByteBuffer model;private boolean closed;private final boolean v6;
 public PpOcrTextDetector(Context context)throws Exception{this(context,false);}
 public PpOcrTextDetector(Context context,boolean v6)throws Exception{this.v6=v6;
  try(AssetFileDescriptor asset=context.getAssets().openFd(v6?"ppocrv6/detection.onnx":"ppocrv5/detection.onnx");FileInputStream in=asset.createInputStream()){
   if(asset.getLength()!=(v6?9880512L:4826518L))throw new IOException("Invalid PP detector size");model=in.getChannel().map(FileChannel.MapMode.READ_ONLY,asset.getStartOffset(),asset.getLength());
  }
  MessageDigest d=MessageDigest.getInstance("SHA-256");d.update(model.duplicate());StringBuilder hex=new StringBuilder();for(byte b:d.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));if(!(v6?"d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e":MODEL_SHA256).contentEquals(hex))throw new IOException("PP detector integrity failure");
  environment=OrtEnvironment.getEnvironment();try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);options.setCPUArenaAllocator(false);options.setMemoryPatternOptimization(false);options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);session=environment.createSession(model,options);}
 }
 public synchronized List<PpOcrDbPostProcessor.Box> detect(Bitmap bitmap)throws Exception{
  if(closed)throw new IllegalStateException("PP detector closed");int w=bitmap.getWidth(),h=bitmap.getHeight();int[]pixels=new int[Math.multiplyExact(w,h)];bitmap.getPixels(pixels,0,w,0,0,w,h);PpOcrDetectionInput input=PpOcrDetectionInput.fromArgb(pixels,w,h,v6?736:640,v6?32:128);
  try(OnnxTensor tensor=OnnxTensor.createTensor(environment,FloatBuffer.wrap(input.values),new long[]{1,3,input.height,input.width});OrtSession.Result result=session.run(Collections.singletonMap(session.getInputNames().iterator().next(),tensor))){
   if(!(result.get(0)instanceof OnnxTensor))throw new IOException("Invalid PP detector output");OnnxTensor output=(OnnxTensor)result.get(0);long[]shape=output.getInfo().getShape();if((shape.length!=3&&shape.length!=4)||shape[0]!=1||(shape.length==4&&shape[1]!=1)||shape[shape.length-2]<1||shape[shape.length-1]<1||shape[shape.length-2]*shape[shape.length-1]>1024*1024)throw new IOException("Unexpected detector dimensions");FloatBuffer b=output.getFloatBuffer();float[]map=new float[b.remaining()];b.get(map);return PpOcrDbPostProcessor.decode(map,Math.toIntExact(shape[shape.length-1]),Math.toIntExact(shape[shape.length-2]),v6?.2f:.3f,v6?.45f:.6f,v6?1.4f:1.5f);
  }
 }
 public static Bitmap crop(Bitmap bitmap,PpOcrDbPostProcessor.Box box){
  float[]p=box.points.clone();for(int i=0;i<8;i+=2){p[i]*=bitmap.getWidth();p[i+1]*=bitmap.getHeight();}int w=Math.max(1,(int)Math.max(Math.hypot(p[2]-p[0],p[3]-p[1]),Math.hypot(p[4]-p[6],p[5]-p[7]))),h=Math.max(1,(int)Math.max(Math.hypot(p[6]-p[0],p[7]-p[1]),Math.hypot(p[4]-p[2],p[5]-p[3])));float[]dest={0,0,w,0,w,h,0,h};android.graphics.Matrix m=new android.graphics.Matrix();if(!m.setPolyToPoly(p,0,dest,0,4))throw new IllegalArgumentException("Degenerate detector crop");Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);new android.graphics.Canvas(out).drawBitmap(bitmap,m,new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));return out;
 }
 @Override public synchronized void close(){if(closed)return;closed=true;try{session.close();}catch(OrtException ignored){}model=null;}
}
