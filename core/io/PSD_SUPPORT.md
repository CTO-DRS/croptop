# PSD / PSB support matrix

What CropTop reads and writes (`psd_reader`, `psd_writer`, `psd_layer_data`, `psd_import`, `PsdAdjustments`). "Interchange" rows
say what survives a CropTop → Photoshop/Photopea → CropTop trip. Verified by the native suites `native_tests_psd*.cpp`
(read, write, read-write-read stability, compositor parity, import tree) and the layered export tests; a corpus of real-world
files from Photoshop and Photopea is still to be collected (the acceptance asks for 50), so rows marked *synthetic* are covered
by generated fixtures only.

| Feature | Read | Write | Notes |
|---|---|---|---|
| PSD (v1) and PSB (v2) | yes | yes | PSB is written automatically above 30,000 px |
| Color modes | RGB, Grayscale, CMYK | RGB | CMYK is converted naively to RGB with a warning |
| Bit depth | 8, 16, 32 (linear light) | 8, 16, 32 | 32-bit is stored as RGBA32F |
| Compression | RAW, RLE, ZIP, ZIP+prediction | all four | |
| Layers (name incl. `luni`, bounds, opacity, fill `iOpa`, visibility, transparency lock) | yes | yes | |
| Blend modes | the Photoshop blend set (PSD keys) incl. pass-through groups | same set | unknown keys read as Normal with a warning |
| Groups (`lsct`/`lsdk`) | yes | yes | nested groups keep order and open/closed state |
| Layer masks (user mask, default colour, disabled) | yes | yes | vector masks are not read; only the pixel user mask is kept |
| Clipping | yes | yes | |
| **Blend If** (composite gray: this layer / underlying, split sliders) | yes | yes | per-channel ranges are read as gray only |
| Adjustment layers | Invert, Threshold, Posterize, Brightness/Contrast, Levels, Curves, Hue/Saturation, Color Balance | the same set | other adjustment types are skipped with a warning |
| Layer effects (`lfx2`) | drop/inner shadow, outer/inner glow, bevel & emboss, satin, color/gradient/pattern overlay, stroke | rendered into pixels when a pattern is missing, otherwise written as descriptors | global light angle/altitude honoured |
| Text layers (`TySh`) | first run's font, size, colour, alignment, point/paragraph → editable text; pixels kept until edited | rendered pixels only (export warns that it is no longer editable text) | rich per-run styling is flattened to the first run |
| Smart objects (`SoLd`) | embedded raster becomes a CropTop smart object source | written as a pixel layer (export warns that smart filters and transform are flattened) | linked/embedded PSB contents are not preserved |
| Shape layers | pixels | pixels | vector data is rasterized |
| ICC profile (resource 1039) | yes | yes | assigned to the document |
| Merged composite | yes | yes | files saved without "Maximize compatibility" are detected |
| Resolution (resource 1005) | yes | yes | |
| Everything else (3D, video, artboards, patterns, timeline) | ignored | not written | reported in the open warnings dialog |

Open warnings (CMYK conversion, skipped adjustments, unknown blend modes, dropped out-of-canvas pixels) appear in the document-open
warnings dialog; export warnings (what was rasterized) appear in the export compatibility dialog.
