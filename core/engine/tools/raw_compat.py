#!/usr/bin/env python3
"""Camera RAW compatibility matrix for the sample fixtures used by native tests.

For each model below, downloads its smallest sample raw from https://raw.pixls.us (CC0) into a cache, runs the host
tool `croptop_raw_check` on it (probe, embedded preview, and the full open the app uses), and rewrites
core/raw/CAMERAS.md with the result. A model passes when the raw probes as decodable, opens to an image whose mean
brightness is neither black nor blown out, and yields an embedded JPEG preview.

  raw_compat.py --tool build/croptop_raw_check [--cache ~/.cache/croptop-raw-samples] [--only "Sony"] [--quick]
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://raw.pixls.us/data/"
# (maker directory, model directory) exactly as raw.pixls.us names them.
MODELS = [
    ("Canon", "EOS R5"), ("Canon", "EOS R6"), ("Canon", "EOS R6 Mark II"), ("Canon", "EOS R7"), ("Canon", "EOS R8"),
    ("Canon", "EOS R10"), ("Canon", "EOS RP"), ("Canon", "EOS 5D Mark IV"), ("Canon", "EOS 6D Mark II"), ("Canon", "EOS 90D"),
    ("Nikon", "D850"), ("Nikon", "D750"), ("Nikon", "D7500"), ("Nikon", "D5600"), ("Nikon", "D3500"),
    ("Nikon", "Z 6"), ("Nikon", "Z 7_2"), ("Nikon", "Z 8"), ("Nikon", "Z 9"), ("Nikon", "Z f"),
    ("Sony", "ILCE-7M3"), ("Sony", "ILCE-7M4"), ("Sony", "ILCE-7RM4"), ("Sony", "ILCE-7RM5"), ("Sony", "ILCE-9M2"),
    ("Sony", "ILCE-6000"), ("Sony", "ILCE-6400"), ("Sony", "ILCE-1"), ("Sony", "DSC-RX100M7"),
    ("Fujifilm", "X-T3"), ("Fujifilm", "X-T4"), ("Fujifilm", "X-T5"), ("Fujifilm", "X-H2"), ("Fujifilm", "X100V"), ("Fujifilm", "X-S10"),
    ("Panasonic", "DC-S5"), ("Panasonic", "DC-G9"), ("Panasonic", "DC-GH5"),
    ("Olympus", "E-M1MarkII"), ("Olympus", "E-M5 Mark III"), ("Olympus", "E-M10 Mark IV"),
    ("Pentax", "K-1"), ("Pentax", "K-3 Mark III"),
    ("Leica", "Q2"), ("Leica", "M10"), ("Sigma", "fp"),
    ("Apple", "iPhone 12 Pro"), ("Google", "Pixel 7 Pro"), ("Google", "Pixel 8 Pro"), ("Samsung", "Galaxy S23 Ultra"),
]
SKIP_EXT = {".txt", ".jpg", ".jpeg", ".xmp", ".png", ".html", ".md5", ".json", ".xml"}
UNITS = {"K": 1 << 10, "M": 1 << 20, "G": 1 << 30}


def open_url(url: str, timeout: int):
    """urlopen with retries: raw.pixls.us resets connections when hit quickly, so back off and try again."""
    for attempt in range(5):
        try:
            return urllib.request.urlopen(url, timeout=timeout)
        except (OSError, urllib.error.URLError):
            if attempt == 4:
                raise
            time.sleep(2 ** attempt)


def listing(maker: str, model: str):
    url = BASE + urllib.parse.quote(maker) + "/" + urllib.parse.quote(model) + "/"
    with open_url(url, 30) as response:
        html = response.read().decode("utf-8", "replace")
    time.sleep(0.5)  # be polite to a volunteer-run server
    files = []
    for row in re.findall(r"<tr>.*?</tr>", html, re.S):
        link = re.search(r'href="([^"?/][^"]*)"', row)
        if not link or os.path.splitext(link.group(1))[1].lower() in SKIP_EXT:
            continue
        size = re.findall(r'>\s*([\d.]+)([KMG]?)\s*<', row)
        nbytes = float(size[-1][0]) * UNITS.get(size[-1][1], 1) if size else float("inf")
        files.append((nbytes, urllib.parse.unquote(link.group(1)), url + link.group(1)))
    return sorted(files)


def fetch(url: str, path: str):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    part = path + ".part"
    with open_url(url, 120) as response, open(part, "wb") as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)
    os.replace(part, path)


def check(tool: str, path: str, quick: bool):
    args = [tool] + (["--quick"] if quick else []) + [path]
    try:
        run = subprocess.run(args, capture_output=True, text=True, timeout=600)
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "timeout after 600 s"}
    line = run.stdout.strip().splitlines()[-1] if run.stdout.strip() else ""
    try:
        result = json.loads(line)
    except json.JSONDecodeError:
        return {"ok": False, "error": (run.stderr.strip() or f"no result, exit {run.returncode}")[:200]}
    result["exit"] = run.returncode
    return result


def verdict(result: dict, quick: bool):
    if not result.get("decodable", False) and "probeMs" in result:
        return "FAIL", result.get("unsupportedReason", "not decodable")
    if "probeMs" not in result:
        return "FAIL", result.get("error", "probe failed")
    note = "" if result.get("previewOk", False) else f" [no JPEG preview: {result.get('previewError', '')}]"
    if quick:
        if not result.get("previewOk", False):
            return "FAIL", "no embedded preview: " + result.get("previewError", "")
        return "OK", f"probe+preview only; preview {result['previewWidth']}x{result['previewHeight']} in {result['previewMs']:.0f} ms"
    if not result.get("openOk", False):
        return "FAIL", result.get("openError", "open failed")
    if not result.get("ok", False):
        return "FAIL", f"mean brightness {result.get('meanBrightness', 0):.3f} (black or blown out)"
    preview = f"preview {result['previewMs']:.0f} ms" if result.get("previewOk") else "no JPEG preview"
    kind = " X-Trans" if result.get("xtrans") else " linear (sRAW/DNG)" if result.get("linear") else ""
    return "OK", (f"{result['width']}x{result['height']}{kind}; open {result['openMs']:.0f} ms; "
                  f"{preview}; brightness {result['meanBrightness']:.2f}{note}")


def write_markdown(path: str, rows):
    ok = sum(1 for r in rows if r[2] == "OK")
    lines = [
        "# Camera RAW compatibility", "",
        "Generated by `core/engine/tools/raw_compat.py` from CC0 sample files on https://raw.pixls.us, decoded with LibRaw via",
        "`croptop_raw_check`. **OK** = decodable mosaic, opens to an image that is neither black nor blown out, embedded JPEG",
        "preview extracted. Do not edit by hand; rerun the script.", "",
        f"**{ok} / {len(rows)} models pass.**", "",
        "| Maker | Model | Result | Sample and detail |", "|---|---|---|---|",
    ]
    for maker, model, status, detail in rows:
        lines.append(f"| {maker} | {model} | {status} | {detail} |")
    with open(path, "w", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tool", required=True, help="path to croptop_raw_check")
    parser.add_argument("--cache", default=os.path.expanduser("~/.cache/croptop-raw-samples"))
    parser.add_argument("--only", default="", help="only makers/models containing this text")
    parser.add_argument("--quick", action="store_true", help="probe and preview only")
    parser.add_argument("--output", default=os.path.join(os.path.dirname(__file__), "..", "..", "raw", "CAMERAS.md"))
    args = parser.parse_args()

    rows = []
    for maker, model in MODELS:
        if args.only and args.only.lower() not in f"{maker} {model}".lower():
            continue
        try:
            files = listing(maker, model)
            if not files:
                rows.append((maker, model, "NO SAMPLE", "raw.pixls.us lists no raw file"))
                continue
            # Smallest sample first; if it fails, try the next few (a model directory can mix formats, e.g. Nikon High Efficiency
            # NEFs, which LibRaw cannot decode, with lossless ones) and note what was skipped.
            skipped = []
            for size, name, url in files[:4]:
                path = os.path.join(args.cache, maker, model, name)
                fetch(url, path)
                status, detail = verdict(check(args.tool, path, args.quick), args.quick)
                if status == "OK":
                    extra = f" (smaller samples failed: {'; '.join(skipped)})" if skipped else ""
                    rows.append((maker, model, status, f"`{name}` ({size / (1 << 20):.0f} MB): {detail}{extra}"))
                    break
                skipped.append(f"`{name}`: {detail[:80]}")
            else:
                rows.append((maker, model, "FAIL", "; ".join(skipped)))
        except Exception as error:  # noqa: BLE001 - one bad model must not stop the matrix
            rows.append((maker, model, "ERROR", str(error)[:160]))
        print(rows[-1][:3], flush=True)
    if not args.only:
        write_markdown(args.output, rows)
        print("wrote", os.path.normpath(args.output))
    return 0 if all(r[2] == "OK" for r in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
