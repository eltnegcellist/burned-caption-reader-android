package jp.hidemaru.burnedcaptionreader.evaluation;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;
import java.util.concurrent.CountDownLatch;

/** Debug-only authorized local MP4 player; no network or YouTube path. */
public final class LocalBenchmarkVideoActivity extends Activity {
    public VideoView video;
    public final CountDownLatch prepared = new CountDownLatch(1);
    public volatile boolean completed;
    public volatile String failure;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout layout=new FrameLayout(this);layout.setBackgroundColor(Color.BLACK);
        video=new VideoView(this);
        int width=getIntent().getIntExtra("width",960),height=getIntent().getIntExtra("height",540);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(width,height,Gravity.CENTER);
        layout.addView(video,params);setContentView(layout);
        video.setOnPreparedListener(player -> {player.setVolume(0,0);video.start();prepared.countDown();});
        video.setOnCompletionListener(player -> completed=true);
        video.setOnErrorListener((player,what,extra) -> {failure="MediaPlayer "+what+"/"+extra;prepared.countDown();return true;});
        video.setVideoPath(getIntent().getStringExtra("video_path"));
    }
    @Override public void onDestroy(){video.stopPlayback();super.onDestroy();}
}
