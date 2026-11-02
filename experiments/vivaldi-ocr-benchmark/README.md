# Android Vivaldi YouTube OCR Benchmark v0.3.0

Development-only benchmark extension, not a production reader. Compare PP-OCRv6 Small and Tesseract Japanese fast/best on an identical captured video frame. The ZIP is produced by GitHub Actions; unzip before using Vivaldi's developer-mode **Load unpacked**. No OCR image is uploaded by the extension.

The extension requires a modern Chromium supporting local WASM. Browser memory/CSP support remains unverified on Android until tested. Each engine's initialization time is separate from OCR time, and optional ground-truth text yields CER. Inspect output for false positives, not just CER. Model assets and JS/WASM are packaged locally. Third-party license notices accompany the build. Artifact retention is one day to conserve Actions storage.

**Do not merge this branch into main** without checking the workflows and app impact. Existing Android app releases are untouched.
