"""Owned synthetic controls; these are NOT PixelCopy or natural-video benchmarks."""
import argparse
import hashlib
import json
from pathlib import Path
import av
import numpy as np
from PIL import Image, ImageDraw, ImageFont

TEXTS = [
    '本日の価格は100円です', '本日の価格は200円です',
    'このサービスを利用できます', 'このサービスを利用できません',
    '東京都で山田太郎さんに会いました',
    '字幕は上から読みます\n二行目にも大切な情報があります',
    '今日は10月12日です\n参加者は25人です\n中止ではありません',
    '同じ字幕がしばらく続きます', '小さい字幕も読み取れますか',
    'これは字幕ではありませんとは言いません',
]

def stamp(ms):
    return f'{ms//3600000:02}:{ms//60000%60:02}:{ms//1000%60:02},{ms%1000:03}'

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('out', type=Path)
    p.add_argument('--font', required=True)
    p.add_argument('--seconds', type=int, default=300)
    p.add_argument('--variants', type=int, default=3)
    a = p.parse_args(); a.out.mkdir(parents=True, exist_ok=True)
    font_hash = hashlib.sha256(Path(a.font).read_bytes()).hexdigest()
    for variant in range(a.variants):
        stem = f'control-{variant+1}'; video = a.out / f'{stem}.mp4'
        container = av.open(str(video), 'w')
        stream = container.add_stream('libx264', rate=10)
        stream.width = 960; stream.height = 540; stream.pix_fmt = 'yuv420p'
        stream.options = {'crf': '18', 'preset': 'ultrafast'}
        events = []; samples = []; indices = {}
        for tick in range(a.seconds * 10):
            seconds = tick/10; event_no = int(seconds//10)
            visible = seconds%10 >= 1 and seconds%10 < 7
            text = TEXTS[event_no % len(TEXTS)] if visible else ''
            # Background changes underneath constant subtitles; fixed title remains.
            image = Image.new('RGB', (960,540), (28,35,45))
            d = ImageDraw.Draw(image)
            if variant >= 1:
                x = (tick*7)%900
                d.rectangle((x,180,x+60,510), fill=(90,80,75))
            titlefont=ImageFont.truetype(a.font,25)
            d.text((25,25),'検証用の固定見出し',font=titlefont,fill=(255,255,255))
            if variant == 2:
                d.text((600,200),'背景の文字 123',font=titlefont,fill=(170,170,170))
            regions=[]
            if text:
                size = 24 if variant == 2 else 34
                font=ImageFont.truetype(a.font,size)
                lines=text.split('\n'); top=510-len(lines)*(size+8)
                for line_no,line in enumerate(lines):
                    w=d.textbbox((0,0),line,font=font)[2]
                    x=(960-w)//2; y=top+line_no*(size+8)
                    color=(160,160,160) if variant==2 and event_no%3==0 else (255,255,255)
                    d.text((x,y),line,font=font,fill=color,stroke_width=2,stroke_fill=(0,0,0))
                    regions.append([max(0,x-4)/960,(y-4)/540,min(960,x+w+4)/960,min(540,y+size+8)/540])
            if tick%100==30 or tick%100==90:
                name=f'{stem}-{tick:04}.png'; image.save(a.out/name)
                samples.append({'id':name,'image':name,'sha256':hashlib.sha256((a.out/name).read_bytes()).hexdigest(),
                    'expected_text':text,'caption_regions':regions,'media_ms':tick*100,
                    'human_verified':False,'annotation_source':'render_spec_pending_visual_verification'})
            if visible and event_no not in indices:
                indices[event_no]=True
                events.append({'start':event_no*10000+1000,'end':min(a.seconds*1000,event_no*10000+7000),'text':text})
            frame=av.VideoFrame.from_ndarray(np.array(image),format='rgb24')
            for packet in stream.encode(frame): container.mux(packet)
        for packet in stream.encode(): container.mux(packet)
        container.close()
        (a.out/f'{stem}.reference.srt').write_text('\n\n'.join(f'{i+1}\n{stamp(e["start"])} --> {stamp(e["end"])}\n{e["text"]}' for i,e in enumerate(events))+'\n',encoding='utf-8')
        manifest={'source':'owned_synthetic_render','natural_video':False,'pixelcopy':False,
            'split':'held_out_control' if variant==2 else 'development_control',
            'video':video.name,'sha256':hashlib.sha256(video.read_bytes()).hexdigest(),
            'font_sha256':font_hash,'seconds':a.seconds,'fps':10,'common_roi_pixels':[0,350,960,190],
            'events':events,'frames':samples}
        (a.out/f'{stem}.manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
        print(video,manifest['sha256'],flush=True)

if __name__=='__main__': main()
