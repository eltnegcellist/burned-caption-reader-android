"""Frozen, exploratory B-only rule. No ground truth or D output is accepted.
Set before inspecting this run's predictions; no threshold search on GT.
Only ML Kit coarse public rows are used. Not a live implementation.
"""
import re
from statistics import median
JAPANESE = re.compile(r'[\u3040-\u30ff\u3400-\u9fff]')

def suspect_three_rows(raw_rows):
    rows = [r for r in raw_rows if len(JAPANESE.findall(r['text'])) >= 2
            and r['right'] - r['left'] >= .15
            and .015 <= r['bottom'] - r['top'] <= .20]
    for region in ('top', 'bottom'):
        selected = [r for r in rows if (r['top'] + r['bottom']) / 2 < .50] if region == 'top' else [r for r in rows if (r['top'] + r['bottom']) / 2 > .65]
        selected.sort(key=lambda r: (r['top'] + r['bottom']) / 2)
        for i in range(len(selected) - 2):
            block = selected[i:i+3]
            centers = [(r['top'] + r['bottom']) / 2 for r in block]
            height = median(r['bottom'] - r['top'] for r in block)
            gaps = [centers[j+1] - centers[j] for j in (0,1)]
            if all(.5*height <= gap <= 2.5*height for gap in gaps):
                return True
    return False
