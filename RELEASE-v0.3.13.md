# v0.3.13: evidence-backed trailing punctuation and short reactions

Coarse OCR from the same selected band now accompanies refined OCR into temporal consensus. Within the existing 1.8-second/four-frame window, explicit punctuation with an exact Japanese sentence stem can repair a short terminal 0/o/b/d/8-like OCR tail. Repairs also update earlier votes, the committed variant, and history comparison. Without matching evidence, numbers are retained. This is deliberately narrow lexical/row-position evidence, not a pixel classifier or general OCR correction.

Short repeated-vowel reactions such as the supplied `えええ00o` (31.05% confidence) can reach refinement after three stable observations when below 35% confidence. Geometry and UI/product filters remain; the TTS confidence threshold is unchanged. Refinement may still fail to recognize a readable reaction.

Validation: eight added regressions, 74 subtitle tests pass locally. Replay of the retained selected/refined observations removes the repeated `前回の定例...` block and the `SEさん、そこを何とか` repeat. Replay reuses recorded selection and approximate confidence, not Android OCR or audio. The `仕様凍結...` row still repeats with unrelated kanji errors; no claim all duplicate speech is solved. Physical-device validation remains necessary.

Auto-pause behavior, disappearance/reappearance identity and general unknown ellipsis shapes remain follow-up work. Test with auto-pause off first. Version code 18.
