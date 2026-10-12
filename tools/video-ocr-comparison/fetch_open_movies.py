"""Verify or obtain the exact public Commons inputs. Never downloads from YouTube.

Default is verification only. --download enables the official published video URLs
and pinned Commons subtitle revisions. Media/full subtitle text stay private.
"""
import argparse,hashlib,json,urllib.parse,urllib.request
from pathlib import Path

def digest(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def main():
    p=argparse.ArgumentParser();p.add_argument('workspace',type=Path);p.add_argument('--download',action='store_true');a=p.parse_args()
    root=a.workspace.resolve();folder=root/'private/open-movies';folder.mkdir(parents=True,exist_ok=True)
    ua={'User-Agent':'burned-caption-reader-android-local-benchmark/1.0 (https://github.com/eltnegcellist/burned-caption-reader-android)'}
    records=[]
    for name in ['elephants','sintel']:
        meta=json.loads((Path(__file__).parent/f'open-movies/{name}.download.json').read_text())
        movie=folder/meta['file'];subtitle=folder/meta['subtitle_file']
        if not movie.exists():
            if not a.download:raise FileNotFoundError(f'{movie}: use --download for official Commons file')
            temp=movie.with_suffix(movie.suffix+'.part')
            with urllib.request.urlopen(urllib.request.Request(meta['download_url'],headers=ua),timeout=120) as response,temp.open('wb') as output:
                while chunk:=response.read(1024*1024):output.write(chunk)
            if digest(temp)!=meta['sha256']:raise ValueError('Downloaded movie hash mismatch; partial file retained')
            temp.replace(movie)
        if digest(movie)!=meta['sha256']:raise ValueError(f'{name}: existing movie hash mismatch')
        if not subtitle.exists():
            if not a.download:raise FileNotFoundError(f'{subtitle}: use --download for pinned Commons revision')
            query=urllib.parse.urlencode({'action':'query','format':'json','prop':'revisions','revids':meta['subtitle_revision'],'rvprop':'ids|content','rvslots':'main'})
            url='https://commons.wikimedia.org/w/api.php?'+query
            with urllib.request.urlopen(urllib.request.Request(url,headers=ua),timeout=60) as response:payload=json.load(response)
            page=next(iter(payload['query']['pages'].values()));revision=page['revisions'][0]
            if revision['revid']!=meta['subtitle_revision']:raise ValueError('Revision changed')
            slot=revision['slots']['main'];text=slot.get('*',slot.get('content'))
            if text is None:raise ValueError('Subtitle content missing')
            raw=text.encode('utf-8')
            if hashlib.sha256(raw).hexdigest()!=meta['subtitle_sha256']:raise ValueError('Subtitle hash mismatch')
            subtitle.write_bytes(raw)
        if digest(subtitle)!=meta['subtitle_sha256']:raise ValueError(f'{name}: existing subtitle hash mismatch')
        records.append({'film':name,'movie_sha256':digest(movie),'subtitle_sha256':digest(subtitle),'subtitle_revision':meta['subtitle_revision'],'verified':True})
        print(name,'source movie and pinned subtitle verified',flush=True)
    out=root/'outputs/open-movie-benchmark/source-verification.json';out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(records,indent=2)+'\n')
if __name__=='__main__':main()
