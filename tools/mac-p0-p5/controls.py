"""Fresh synthetic safety controls, never presented as natural video accuracy."""
import argparse, json, subprocess
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from evaluate import enc,parse,quant

def main():
    p=argparse.ArgumentParser();p.add_argument('--workspace',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True);a=p.parse_args()
    w=a.workspace.resolve();out=w/'outputs/ocr-p0-p5-experiment-20261013';private=out/'private/controls';private.mkdir(parents=True,exist_ok=True)
    font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',14)
    controls=[]
    def fixture(name,before,after,changed=True,trust=True,top=.745,bottom=.80,generation=1):
        paths=[]
        for suffix,im in [('a',before),('b',after)]:
            path=private/f'{name}-{suffix}.png';im.save(path);paths.append(path)
        controls.append([name,*map(str,paths),str(changed).lower(),str(trust).lower(),str(top),str(bottom),str(generation)])
    blank=Image.new('RGB',(960,540),(0,0,0));caption=blank.copy();ImageDraw.Draw(caption).text((310,410),'100',font=font,fill='white')
    changed=blank.copy();ImageDraw.Draw(changed).text((310,410),'200',font=font,fill='white')
    fixture('unchanged',caption,caption,False)
    fixture('first-appearance',blank,caption,True,False)
    fixture('caption-disappearance',caption,blank)
    fixture('digit-100-to-200',caption,changed)
    tinyfont=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',8)
    one=blank.copy();two=blank.copy();ImageDraw.Draw(one).text((321,403),'1',font=tinyfont,fill='white');ImageDraw.Draw(two).text((321,403),'2',font=tinyfont,fill='white')
    fixture('unsampled-small-digit-1-to-2',one,two)
    # Explicit sub-grid glyph/cell. Independent visual change located between sample centers.
    small=blank.copy();ImageDraw.Draw(small).rectangle((321,403,327,410),fill='white')
    fixture('unsampled-short-glyph-appearance',blank,small)
    red=blank.copy();green=blank.copy();ImageDraw.Draw(red).rectangle((321,403,327,410),fill=(100,0,0));ImageDraw.Draw(green).rectangle((321,403,327,410),fill=(0,51,0))
    fixture('unsampled-equal-luma-red-green',red,green)
    fixture('sampled-equal-luma-red-green',Image.new('RGB',(960,540),(100,0,0)),Image.new('RGB',(960,540),(0,51,0)))
    fixture('moving-background',caption,Image.new('RGB',(960,540),(100,100,100)))
    fixture('generation-reset',caption,caption,False,True,generation=2)
    upper=blank.copy();ImageDraw.Draw(upper).text((310,60),'first\nsecond\nthird',font=font,fill='white')
    fixture('upper-three-lines-appearance',blank,upper,True,True,top=.08,bottom=.20)
    dark=blank.copy();ImageDraw.Draw(dark).text((310,410),'NO',font=font,fill=(35,35,35))
    fixture('dark-negation-appearance',blank,dark)
    manifest=private/'shadow.tsv';manifest.write_text('\n'.join('\t'.join(v) for v in controls)+'\n')
    java=a.jdk/'bin/java';javac=a.jdk/'bin/javac';classes=out/'classes-pr49'
    subprocess.run([str(javac),'-cp',str(classes),'-d',str(classes),str(w/'repo/tools/mac-p0-p5/ShadowControls.java')],check=True)
    result=subprocess.run([str(java),'-Djava.awt.headless=true','-cp',str(classes),'ShadowControls',str(manifest)],capture_output=True,text=True,check=True)
    rows=[]
    for line in result.stdout.splitlines():
        v=line.split('\t');rows.append({'id':v[0],'gold_changed':v[1]=='true','would_skip':v[2]=='true','reason':v[3],
           'elapsed_us':float(v[4]),'peak_channel_delta':int(v[5]),'band_luma_delta':float(v[6]),'false_skip':v[1]=='true' and v[2]=='true'})
    # Reproduce exactly the recorded price test through each pinned tracker, no pixel OCR.
    header=[0,'検証用の固定見出し',81.72743,.027058823,.048117153,.25647059,.09205021]
    price=[1,'',84.798175,.30588236,.86820084,.6870588,.92677826]
    lines=[];groups=[];i=0
    for group,base,n,text in [('100',1000,8,'本日の価格は100円です'),('blank',7000,5,None),('200',11000,8,'本日の価格は200円です')]:
        for j in range(n):
            lines.append('\t'.join(['F',str(i),str(base+480*j),'unused','false','false']));groups.append(group)
            for r in [header]+([price[:1]+[text]+price[2:]] if text else []):
                vals=[str(r[0]),enc(r[1])]+list(map(str,r[2:]))+['0','-1','']
                for tag in ['R','D']:lines.append('\t'.join([tag]+vals))
            i+=1
    inp=private/'price.tsv';inp.write_text('\n'.join(lines)+'\n');price_metrics={}
    for label in ['main','pr49']:
        r=subprocess.run([str(java),'-cp',str(out/('classes-'+label)),'ObservationReplay',str(inp),'false'],capture_output=True,text=True,check=True)
        path=private/f'price-{label}.tsv';path.write_text(r.stdout);parsed=parse(path)
        price_metrics[label]={g:sum(bool(v['selected']) for i,v in parsed.items() if groups[i]==g) for g in ['100','blank','200']}
    summary={'shadow':rows,'false_skips':sum(r['false_skip'] for r in rows),'controls':len(rows),'price_recorded_observations':price_metrics,'scope':'Synthetic pixel safety controls and recorded OCR-only price replay; no actual OCR or TTS'}
    (out/'controls.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n');print(json.dumps(summary,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
