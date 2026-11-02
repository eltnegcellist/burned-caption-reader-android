# Android Vivaldi YouTube OCR Benchmark v0.3.1

Development-only benchmark extension, not a production reader. Compare PP-OCRv6 Small and Tesseract Japanese fast/best on an identical captured video frame. The ZIP is produced by GitHub Actions; unzip before using Vivaldi's developer-mode **Load unpacked**. No OCR image is uploaded by the extension.

The extension requires a modern Chromium supporting local WASM. Browser memory/CSP support remains unverified on Android until tested. Each engine's initialization time is separate from OCR time, and optional ground-truth text yields CER. Inspect output for false positives, not just CER. Model assets and JS/WASM are packaged locally. Third-party license notices accompany the build. Artifact retention is one day to conserve Actions storage.

**Do not merge this branch into main** without checking the workflows and app impact. Existing Android app releases are untouched.

## v0.3.1: Chrome Manifest V3 CSP compatibility

In v0.3.0, bundled OpenCV.js (part of PaddleOCR.js) called `new Function()` during startup, which Chromium extension-page CSP forbids. The build now runs `scripts/patch_opencv_csp.py` after bundling to replace two Emscripten embind code generators with CSP-safe functions; no unsafe-eval exception is added. The build fails if unexpected upstream dynamic-function code remains. Tesseract and PP-OCRv6 models are otherwise unchanged.

The new ZIP passes syntax/CSP static checks; **PaddleOCR inference in Android Vivaldi still needs device verification**. Disable earlier versions before loading the unpacked v0.3.1 folder.
