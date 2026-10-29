# Third-party components distributed with GoBal

GoBal retains the upstream project's [MIT license](LICENSE.md). The following
dependencies retain their respective upstream licenses; they are not
relicensed under the project's MIT license.

| File | Source | License | SHA-256 |
| --- | --- | --- | --- |
| `app/libs/sherpa-onnx-1.13.8.aar` | [sherpa-onnx v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) | [Apache-2.0](licenses/sherpa-onnx-LICENSE.txt) | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` |
| `app/src/main/assets/tessdata/eng.traineddata` | [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) | [Apache-2.0](licenses/tessdata-fast-LICENSE.txt) | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |
| `app/src/main/assets/tessdata/chi_sim.traineddata` | [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) | [Apache-2.0](licenses/tessdata-fast-LICENSE.txt) | `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730` |

The OCR data and the local speech-runtime dependency were compared byte-for-byte
by SHA-256 with upstream downloads when preparing this source publication.
The AAR is not redistributed in this repository: run
`python scripts/fetch_sherpa_onnx.py` to download it directly from the pinned
upstream release. The included sherpa-onnx license applies to that project;
downstream APK distributors must also retain the applicable notices for the
runtime's bundled native dependencies.

The existing llama.cpp notice is preserved at
[`app/src/main/assets/LICENSE-llama.cpp.txt`](app/src/main/assets/LICENSE-llama.cpp.txt).
Cloudflared and ngrok-java remain Git submodules with their own license files.
Gradle-managed dependencies retain the licenses and notices supplied by their authors.
