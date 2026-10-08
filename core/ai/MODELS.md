# On-device models

Only models we may ship in a commercial app are registered (`ModelSpec.problems()` refuses entries whose license is not
`commercialUse` or names a non-commercial / research / AGPL / GPL license). This file is the human-readable candidate list;
`ModelRegistry` is the machine-readable one.

**What is verified and what is not.** The *code repository* license column was read from the GitHub license API on
2026-09-29. For each **shipped** row, the license of the *trained weights* was read from the model page it is downloaded from
(recorded in the row and in `app/src/main/assets/ai/models.json`, which the app shows in Settings ▸ Open-source licenses). The
terms of the *training data* and of third-party conversions are recorded per row where they were checked; where a row says "not
reviewed", that review is still open and must be closed before a store release. Rows marked "pending" are candidates only and
are not downloaded by the app. Nothing here is a legal opinion.

| Task | Candidate | Code repo | Code license (2026-09-29) | Weights / data review |
|---|---|---|---|---|
| Promptable object select | SAM 2.1 Hiera-Tiny (LiteRT encoder v2 + decoder v2, litert-community) | facebookresearch/sam2 | Apache-2.0 (model pages); SA-1B / SA-V are Meta's licensed datasets | shipped as `sam2-encoder` (80 MB) + `sam2-decoder` (17 MB), optional upgrade over `magic-touch`, device-verified 2026-10-07 |
| Object select (click) | MediaPipe MagicTouch | google-ai-edge/mediapipe | Apache-2.0 (model card) | shipped as `magic-touch` (6 MB) |
| Subject selection | DeepLab v3 (MediaPipe image segmenter) | tensorflow/models | Apache-2.0 | shipped as `deeplab-v3` (3 MB), 21 PASCAL VOC classes |
| Promptable object select (alternative) | MobileSAM | ChaoningZhang/MobileSAM | Apache-2.0 | pending (superseded by SAM 2.1) |
| Promptable object select | EfficientSAM | yformer/EfficientSAM | Apache-2.0 | pending |
| Promptable object select (reference) | Segment Anything | facebookresearch/segment-anything | Apache-2.0 | pending |
| Background removal / cut-out | DIS IS-Net general-use (LiteRT, litert-community) | xuebinqin/DIS | Apache-2.0 (model page); DIS5K training data terms not reviewed | shipped as `isnet-general` (176 MB), used by Remove Background and, when installed, Select Subject |
| Subject / salient segmentation | U²-Net | xuebinqin/U-2-Net | Apache-2.0 | pending |
| Portrait matting | MODNet | ZHKKKe/MODNet | Apache-2.0 | pending |
| Inpainting | LaMa-Dilated (LiteRT, via Qualcomm AI Hub tooling) | mlboydaisuke/LaMa-Dilated-LiteRT (from advimman/lama) | Apache-2.0 (model page); conversion tool terms not reviewed | shipped as `lama-dilated`, 182 MB, device-verified 2026-10-07 |
| Depth for Lens Blur | Depth Anything V2 (Small only) | DepthAnything/Depth-Anything-V2 | Apache-2.0 | pending; the upstream Base/Large/Giant weights carry a different, non-commercial license and must not be used |
| Depth (alternative) | MiDaS v2.1 small | isl-org/MiDaS | MIT | shipped as `midas-small` (66 MB), device-verified 2026-10-07; training-data terms not reviewed |
| Upscale ×2/×4 | Real-ESRGAN realesr-general-x4v3 (LiteRT, litert-community) | xinntao/Real-ESRGAN | BSD-3-Clause | shipped as `realesr-general-x4v3`, 3.5 MB, device-verified 2026-10-07 |
| Upscale / restoration | SwinIR | JingyunLiang/SwinIR | Apache-2.0 | pending |
| Denoise | NAFNet SIDD-width32 (LiteRT fp16, litert-community) | megvii-research/NAFNet | MIT (weights/code); the SIDD training dataset is research-use, review required | shipped as `nafnet-sidd`, 62 MB, device-verified 2026-10-07 |

**Excluded.** BRIA RMBG-1.4 (background removal): distributed under a non-commercial license. Do not register it.

## Registering a model

1. Confirm the weights license and training-data terms on the model card and copy the attribution text.
2. Convert to LiteRT (`.tflite`) and record the file's SHA-256 and size.
3. Host it as a Play Asset Delivery on-demand pack (or a fixed HTTPS URL for development builds).
4. Add the `ModelSpec` to the bundled manifest with `license.commercialUse = true`, the SPDX id and the attribution; the registry rejects
   anything else.
5. Add the row to `core/engine/THIRD_PARTY.md`-style notices shown in the app's Licenses screen.
