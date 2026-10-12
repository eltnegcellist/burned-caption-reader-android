"""Render authorized Commons films locally. Gold must be pixel reviewed after rendering.

Media and reference text stay private. Does not infer truth from any OCR output.
"""
import argparse, hashlib, json, subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw
from score_srt import read_srt

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def stamp(ms):
    h, ms=divmod(round(ms),3600000);m,ms=divmod(ms,60000);s,ms=divmod(ms,1000)
    return f'{h:02}:{m:02}:{s:02},{ms:03}'
def main():
    p=argparse.ArgumentParser();p.add_argument('workspace',type=Path);a=p.parse_args()
    root=a.workspace.resolve();ff=root/'upstream/Subtitle Edit.app/Contents/MacOS/ffmpeg'
    public=root/'outputs/open-movie-benchmark';public.mkdir(parents=True,exist_ok=True)
    private=root/'private/open-movie-benchmark';private.mkdir(parents=True,exist_ok=True)
    record={'duration_ms':300000,'fps':24,'width':960,'split_frozen_before_OCR':True,
      'style':"FontName=Hiragino Sans W4,FontSize=24,Outline=2,MarginV=20",
      'kind':'Commons CG films with locally burned Japanese TimedText; not original Japanese hardsubs',
      'reference_verified_from_pixels':False,'films':[]}
    for name,ext,height,role in [('elephants','webm',540,'development'),('sintel','ogv',408,'held-out-fixed-settings')]:
        source=root/f'private/open-movies/{name}.{ext}';srt=root/f'private/open-movies/{name}.source-ja.srt'
        folder=private/name;folder.mkdir(exist_ok=True);movie=folder/f'{name}-300s.mp4'
        refs=[dict(r,end=min(r['end'],300000)) for r in read_srt(srt) if r['start']<300000]
        reference=folder/'reference.srt'
        reference.write_text('\n\n'.join(f'{i+1}\n{stamp(r["start"])} --> {stamp(r["end"])}\n{r["text"]}' for i,r in enumerate(refs))+'\n')
        vf=f"scale=960:-2,fps=24,subtitles={srt}:force_style='{record['style']}'"
        command=[str(ff),'-hide_banner','-y','-i',str(source),'-t','300','-an','-vf',vf,
                 '-c:v','libx264','-preset','veryfast','-crf','20',str(movie)]
        if not movie.exists():
            with (public/f'{name}-render.log').open('w') as log: subprocess.run(command,stdout=log,stderr=log,check=True)
        frames=[];sheet=[]
        times=[(int((r['start']+r['end'])/2),i,r['text']) for i,r in enumerate(refs)]
        blanks=[t for t in range(1000,300000,15000) if not any(r['start']-500<=t<=r['end']+500 for r in refs)]
        times += [(t,-1,'') for t in blanks[:10]]
        for t,i,text in sorted(times):
            file=folder/f'frame-{t:06d}.png'
            subprocess.run([str(ff),'-hide_banner','-loglevel','error','-y','-ss',str(t/1000),'-i',str(movie),'-frames:v','1',str(file)],check=True)
            row={'id':f'{name}-{t:06d}','image':file.name,'mono_ms':t,'sha256':sha(file),
                 'expected_text':text,'reference_event_index':i,'caption_regions':[[0,(height-180)/height,1,1]]}
            frames.append(row)
            tile=Image.new('RGB',(960,210),'#dddddd');tile.paste(Image.open(file).crop((0,height-180,960,height)),(0,30))
            ImageDraw.Draw(tile).text((8,6),f'{name} / event {i+1} / {t/1000:.3f} sec / {"blank" if i<0 else "subtitle"}',fill='black')
            sheet.append(tile)
        for batch in range(0,len(sheet),6):
            page=Image.new('RGB',(960,210*len(sheet[batch:batch+6])),'white')
            for j,tile in enumerate(sheet[batch:batch+6]):page.paste(tile,(0,j*210))
            page.save(folder/f'review-{batch//6+1}.png')
        manifest={'id':name+'-open-film-300s','source':'authorized_commons_film_local_subtitle_render',
          'pixelcopy':False,'ground_truth_source':'pending_visual_review','reference_verified_from_pixels':False,
          'manual_caption_regions':True,'common_roi_pixels':[0,height-180,960,180],
          'video_sha256':sha(movie),'frames':frames}
        (folder/'dataset.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
        record['films'].append({'id':name,'role':role,'source_sha256':sha(source),'subtitle_sha256':sha(srt),
          'video_sha256':sha(movie),'reference_sha256':sha(reference),'source_time_start_ms':0,
          'events':len(refs),'roi':[0,height-180,960,180],'height':height,'total_video_frames':7200,
          'image_hashes':[{k:r[k] for k in ['id','mono_ms','sha256','reference_event_index']} for r in frames],
          'render_command':command,'reference_verified_from_pixels':False})
        print(f'{name}: rendered {len(refs)} events / {len(frames)} images; review pending',flush=True)
    (public/'inputs.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':main()
