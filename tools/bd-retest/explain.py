#!/usr/bin/env python3
"""Posthoc explanation from private saved stages, exporting IDs/numbers only.
No new inference and no change to production selection policy.
"""
import argparse,json,math
from pathlib import Path
from analyze import key,distance

def main():
    p=argparse.ArgumentParser();p.add_argument('--results',type=Path,required=True);p.add_argument('--numeric-frames',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    out={'scope':'Posthoc saved-output accounting, not an inference ablation or deployable oracle. Frozen dev-cat GT places captions above .6 and visible nameplates below .6.','datasets':{},'paired_mean_timing':{}}
    for name in ['dev-cat','dev-watch','heldout']:
        runs={mode:json.loads((a.results/f'{name}-{mode}-1.json').read_text())['frames'] for mode in ['v6_rec','v6_both']};details={}
        for mode,frames in runs.items():
            fallbacks=[];position_counterfactual=[]
            for frame in frames:
                bands=frame['stages']['bands'];candidate='\n'.join(b['refined'] for b in bands)
                alternative_edits=distance(key(frame['expected_text']),key(candidate))
                if alternative_edits<frame['edit_distance'] and any(key(b['selected'])==key(b['coarse']) and key(b['refined'])!=key(b['coarse']) for b in bands):
                    fallbacks.append(dict(id=frame['id'],selected_edits=frame['edit_distance'],concatenated_refine_edits=alternative_edits,delta=frame['edit_distance']-alternative_edits))
                if name=='dev-cat':
                    top_text='\n'.join(b['selected'] for b in bands if b['top']<.6)
                    top_edits=distance(key(frame['expected_text']),key(top_text))
                    if top_edits!=frame['edit_distance']:position_counterfactual.append(dict(id=frame['id'],before_edits=frame['edit_distance'],top_only_edits=top_edits,delta=frame['edit_distance']-top_edits))
            d=dict(final_edits=sum(f['edit_distance'] for f in frames),saved_refine_better_fallback_examples=fallbacks)
            if name=='dev-cat':d['GT_position_posthoc_exclusion']=position_counterfactual
            details[mode]=d
        out['datasets'][name]=details
    # Oracle uses GT to choose among EXISTING B strings; never deploy this policy.
    bound={'method':'GT-position exclusion of cat nameplate bands, then GT-best choice of selected/refined whole-band text; no D output or new inference',
           'uses_ground_truth':True,'additional_ocr_inferences':0,'selection_runtime_ms':None,'datasets':{}}
    for name in ['dev-cat','dev-watch','heldout']:
        frames=json.loads((a.results/f'{name}-v6_rec-1.json').read_text())['frames']
        original=trimmed=best=characters=0
        for frame in frames:
            truth=key(frame['expected_text']);characters+=len(truth);original+=frame['edit_distance']
            bands=frame['stages']['bands']
            if name=='dev-cat':bands=[b for b in bands if b['top']<.6]
            selected=distance(truth,key('\n'.join(b['selected'] for b in bands)))
            refined=distance(truth,key('\n'.join(b['refined'] for b in bands)))
            trimmed+=selected;best+=min(selected,refined)
        bound['datasets'][name]=dict(reference_characters=characters,B_original_edits=original,GT_nameplate_exclusion_edits=trimmed,GT_best_existing_B_edits=best)
    bound['overall']={k:sum(d[k] for d in bound['datasets'].values()) for k in bound['datasets']['dev-cat']}
    bound['overall']['cer']=bound['overall']['GT_best_existing_B_edits']/bound['overall']['reference_characters']
    out['B_only_saved_output_oracle']=bound
    cat=json.loads((a.results/'dev-cat-v6_rec-1.json').read_text())['frames'];out['fallback_gate_examples']=[]
    for frame in cat:
        if frame['id'] not in ['00000001791806940101','00000001791806940115','00000001791806940125','00000001791806940142']:continue
        band=frame['stages']['bands'][0];coarse=key(band['coarse']);refined=key(band['refined'])
        out['fallback_gate_examples'].append(dict(id=frame['id'],coarse_characters=len(coarse),refined_characters=len(refined),required_min_characters=math.ceil(len(coarse)*.85),similarity=1-distance(coarse,refined)/max(len(coarse),len(refined)),required_similarity=.80,refinement_backend=band['refinement_backend']))
    public=json.loads(a.numeric_frames.read_text())
    for name in ['dev-cat','dev-watch','heldout']:
        values={m:[] for m in ['v6_rec','v6_both']}
        for frame in [f for f in public if f['dataset']==name]:
            for repeat in range(3):
                if any(frame['passes'][m][repeat]['cold'] for m in values):continue
                for mode in values:
                    f=frame['passes'][mode][repeat];d=f['engine_delta'];t={k:d[k+'_ns']/1e6 for k in ['mlkit','detector','recognizer']}
                    t.update(selection=f['selection_ns']/1e6,crop=f['crop_ns']/1e6);t['residual']=f['pipeline_ms']-sum(t.values());t['full']=f['pipeline_ms'];values[mode].append(t)
        avg={m:{k:sum(x[k] for x in xs)/len(xs) for k in xs[0]} for m,xs in values.items()}
        out['paired_mean_timing'][name]=dict(warm_observations=len(values['v6_rec']),modes=avg,D_minus_B={k:avg['v6_both'][k]-avg['v6_rec'][k] for k in avg['v6_rec']})
    a.output.write_text(json.dumps(out,indent=2)+'\n')
if __name__=='__main__':main()
