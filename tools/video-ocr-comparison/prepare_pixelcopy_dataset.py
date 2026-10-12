"""Select nearest saved Android frame to pre-OCR frozen review times.

Creates a PENDING review manifest. No result-based selection and no OCR-derived truth.
"""
import argparse,hashlib,json
from pathlib import Path
from PIL import Image,ImageDraw
from score_srt import read_srt

def main():
    p=argparse.ArgumentParser();p.add_argument('workspace',type=Path);a=p.parse_args();root=a.workspace.resolve()
    for name,height in [('elephants',540),('sintel',408)]:
        private=root/f'private/open-movie-benchmark/{name}'
        saved=json.loads((root/f'outputs/open-movie-benchmark/{name}/android-video.json').read_text())
        frozen=json.loads((private/'dataset.json').read_text());ref=read_srt(private/'reference.srt')
        folder=private/'pixelcopy-dataset';folder.mkdir(exist_ok=True)
        rows=[];tiles=[]
        for target in frozen['frames']:
            f=min(saved['frames'],key=lambda s:abs(s['mono_ms']-target['mono_ms']))
            # Match by independently frozen source interval; never by OCR output.
            active=[i for i,r in enumerate(ref) if r['start']<=f['mono_ms']<r['end']]
            expected=[target['reference_event_index']] if target['reference_event_index']>=0 else []
            if active!=expected:raise ValueError('Nearest capture falls outside its frozen gold interval')
            source=private/'android-pixelcopy'/f['image'];raw=source.read_bytes()
            if hashlib.sha256(raw).hexdigest()!=f['sha256']:raise ValueError('Saved PixelCopy hash changed')
            image=Image.open(source)
            if image.size!=(960,height):raise ValueError('Unexpected capture geometry')
            output=folder/Path(f['image']).name;output.write_bytes(raw)
            rows.append({'id':name+'-pixelcopy-'+str(f['mono_ms']),'image':output.name,'mono_ms':f['mono_ms'],
                'sha256':f['sha256'],'expected_text':target['expected_text'],
                'reference_event_index':target['reference_event_index'],
                'caption_regions':[[0,(height-180)/height,1,1]]})
            tile=Image.new('RGB',(960,210),'#eeeeee');tile.paste(image.crop((0,height-180,960,height)),(0,30))
            ImageDraw.Draw(tile).text((8,6),f"PIXELCOPY {name} event {target['reference_event_index']+1} / {f['mono_ms']} ms",fill='black');tiles.append(tile)
        for start in range(0,len(tiles),6):
            batch=tiles[start:start+6];sheet=Image.new('RGB',(960,210*len(batch)),'white')
            for i,tile in enumerate(batch):sheet.paste(tile,(0,210*i))
            sheet.save(folder/f'review-{start//6+1}.png')
        d={'id':name+'-pixelcopy-images','source':'authorized_commons_film_android_pixelcopy',
            'pixelcopy':True,'ground_truth_source':'pending_visual_review','reference_verified_from_pixels':False,
            'manual_caption_regions':True,'common_roi_pixels':[0,height-180,960,180],
            'video_sha256':saved['video_sha256'],'selection':'nearest saved frame to pre-OCR frozen event midpoints and blank times',
            'frames':rows}
        (folder/'dataset.json').write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
        print(name,len(rows),'PixelCopy images pending independent visual review')
if __name__=='__main__':main()
