"""Copy an explicit media/text-free evidence allowlist into the verification repo.

Full OCR/reference files stay in Mac outputs. No upload/commit is performed here.
"""
import argparse,hashlib,json,shutil
from pathlib import Path
from score_srt import norm,edits

def assert_no_caption_text(value):
    banned={'text','expected_text','reference_text','prediction_text','raw_naive_y_sort_text','coarseLines','readings'}
    if isinstance(value,dict):
        for k,v in value.items():
            if k in banned:raise ValueError('Caption text is not part of the public JSON allowlist: '+k)
            assert_no_caption_text(v)
    elif isinstance(value,list):
        for v in value:assert_no_caption_text(v)

def main():
    p=argparse.ArgumentParser();p.add_argument('workspace',type=Path);a=p.parse_args();root=a.workspace.resolve()
    local=root/'outputs/open-movie-benchmark';repo=root/'benchmark/tools/video-ocr-comparison';public=repo/'open-movies';public.mkdir(exist_ok=True)
    mapping={'inputs.json':'benchmark-inputs.json','pixelcopy-inputs.json':'pixelcopy-inputs.json','metrics.json':'benchmark-metrics.json',
        'provenance.json':'benchmark-provenance.json','commands.json':'benchmark-commands.json','source-verification.json':'source-verification.json',
        'same-input-check.json':'same-input-check.json','subtitleedit-gui-status.json':'subtitleedit-gui-status.json',
        'native-label-guard.json':'native-label-guard.json'}
    for source,target in mapping.items():
        data=json.loads((local/source).read_text());assert_no_caption_text(data)
        text=json.dumps(data,ensure_ascii=False,indent=2).replace(str(root),'$WORKSPACE')
        (public/target).write_text(text+'\n')
    image_evidence=[];layout=[]
    for film in ['elephants','sintel']:
        for file in sorted((local/film).glob('images-*.json')):
            if file.name.endswith('-resource.json'):continue
            data=json.loads(file.read_text())
            for f in data['frames']:
                expected=f['expected_text'];actual=f.get('text',f.get('ocr_text',''))
                image_evidence.append({'film':film,'output':file.name,'id':f['id'],'input_png_sha256':f['sha256'],
                    'reference_characters':len(norm(expected)),'predicted_characters':len(norm(actual)),
                    'normalized_edit_distance':edits(norm(expected),norm(actual)),
                    'normalized_exact':norm(expected)==norm(actual),'caption_present':bool(norm(expected)),
                    'ocr_ms':f.get('ocr_ms',f.get('ocr_processing_ms')),
                    'prediction_utf8_sha256':hashlib.sha256(actual.encode()).hexdigest(),
                    'reference_utf8_sha256':hashlib.sha256(expected.encode()).hexdigest()})
            if file.name.startswith('images-se-layout-'):
                frames=data['frames'];chars=sum(len(norm(f['expected_text'])) for f in frames)
                layout.append({'film':film,'scope':data['scope'],'same_ocr_observations':True,'additional_ocr_calls':0,
                    'naive_y_sort_character_edits':sum(edits(norm(f['expected_text']),norm(f['raw_naive_y_sort_text'])) for f in frames),
                    'official_layout_character_edits':sum(edits(norm(f['expected_text']),norm(f['text'])) for f in frames),
                    'reference_characters':chars})
    for name,data in [('image-evidence.json',image_evidence),('layout-comparison.json',layout)]:
        assert_no_caption_text(data);(public/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
        (local/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    for name in ['scorer-tests-final.log','image-runner-tests-final.log']:
        shutil.copyfile(local/name,public/name)
    for name in ['OPEN-MOVIES.md','OPEN-MOVIES-REPRODUCE.md','IMPLEMENTATION-PLAN.md']:
        shutil.copyfile(repo/name,local/name)
    reproduction=local/'reproduction';reproduction.mkdir(exist_ok=True)
    for file in repo.glob('*.py'):shutil.copyfile(file,reproduction/file.name)
    for file in repo.glob('*.sh'):shutil.copyfile(file,reproduction/file.name)
    shutil.copytree(repo/'se-source',reproduction/'se-source',dirs_exist_ok=True,ignore=shutil.ignore_patterns('bin','obj'))
    print('Public numeric/hash evidence prepared; full captions/media retained locally.')
if __name__=='__main__':main()
