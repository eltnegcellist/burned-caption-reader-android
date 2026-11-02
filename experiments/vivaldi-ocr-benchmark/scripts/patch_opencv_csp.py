#!/usr/bin/env python3
"""Make Emscripten OpenCV.js embind compatible with extension MV3 CSP.

Only two local code generators are replaced, without weakening Content Security
Policy. This guards against silently shipping incompatible upstream changes.
"""
from pathlib import Path
import sys

START_NAMED = '          function createNamedFunction(name, body) {'
END_NAMED = '          function extendError('
NAMED_REPLACEMENT = '''          function createNamedFunction(name, body) {
            name = makeLegalFunctionName(name);
            function cspSafeNamedFunction() {
              "use strict";
              return body.apply(this, arguments);
            }
            Object.defineProperty(cspSafeNamedFunction, "name", { value: name, configurable: true });
            return cspSafeNamedFunction;
          }
'''
START_DYN = '            function makeDynCaller(dynCall2) {'
END_DYN = '            var fp;'
DYN_REPLACEMENT = '''            function makeDynCaller(dynCall2) {
              return function cspSafeDynCaller() {
                return dynCall2(rawFunction, ...arguments);
              };
            }
'''

def replace_between(src: str, start: str, end: str, replacement: str) -> str:
    assert src.count(start) == 1, f'Expected exactly one start marker: {start}'
    pos = src.index(start)
    endpos = src.index(end, pos)
    before = src[pos:endpos]
    assert before.count('new Function(') == 1, f'Unexpected upstream bundle content at {start}'
    return src[:pos] + replacement + src[endpos:]

def main():
    path = Path(sys.argv[1])
    source = path.read_text(encoding='utf-8')
    source = replace_between(source, START_NAMED, END_NAMED, NAMED_REPLACEMENT)
    source = replace_between(source, START_DYN, END_DYN, DYN_REPLACEMENT)
    assert 'new Function(' not in source, 'Remaining new Function() detected in PaddleOCR bundle'
    path.write_text(source, encoding='utf-8')
    print(f'MV3 CSP patch applied: {path}')

if __name__ == '__main__':
    main()
