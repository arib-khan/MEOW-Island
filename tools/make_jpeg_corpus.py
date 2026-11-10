#!/usr/bin/env python3
"""Builds a set of JPEG files with Pillow (libjpeg) plus the reference pixels Pillow decodes from them, for firmware/host-tests/jpeg_test.cpp.
Usage: python3 tools/make_jpeg_corpus.py OUTDIR      (needs: pip install pillow)"""
import sys, os, math, random, io
from PIL import Image, ImageDraw, ImageFilter
out = sys.argv[1]; os.makedirs(out, exist_ok=True); random.seed(11)

def photo(w, h, seed):
    r = random.Random(seed); im = Image.new('RGB', (w, h)); px = im.load(); ph = r.random() * 6
    for y in range(h):
        for x in range(w):
            n = r.randint(-10, 10)
            px[x, y] = (max(0, min(255, int(120 + 90 * math.sin(x / 37 + ph) * math.cos(y / 53) + n))), max(0, min(255, int(110 + 80 * math.sin((x + y) / 61 + ph) + n))),
                        max(0, min(255, int(130 + 70 * math.cos(x / 29 - y / 71) + n))))
    d = ImageDraw.Draw(im); d.ellipse((w // 4, h // 4, 3 * w // 4, 3 * h // 4), fill=(250, 210, 90)); d.rectangle((min(5, w - 1), min(5, h - 1), max(6, w // 3), max(6, h // 6)), fill=(20, 30, 200)); d.line((0, h - 1, w, 0), fill=(255, 0, 0), width=3)
    return im.filter(ImageFilter.GaussianBlur(0.8))

manifest = []
def save(name, im, **kw):
    b = io.BytesIO(); im.save(b, 'JPEG', **kw); data = b.getvalue(); open(f'{out}/{name}.jpg', 'wb').write(data)
    ref = Image.open(io.BytesIO(data)).convert('RGB'); open(f'{out}/{name}.rgb', 'wb').write(ref.tobytes())
    manifest.append(f'{name} {ref.width} {ref.height} {len(data)} {kw.get("subsampling", 2)}')

base = photo(240, 320, 1)
for q in (15, 30, 50, 75, 95):
    for ss in (0, 1, 2):
        save(f'p_q{q}_s{ss}', base, quality=q, subsampling=ss)
save('p_opt', base, quality=40, optimize=True); save('p_opt444', base, quality=60, optimize=True, subsampling=0)
save('p_dri', base, quality=50, restart_marker_blocks=7); save('p_dri_rows', base, quality=50, subsampling=0, restart_marker_rows=1)
for (w, h) in ((17, 13), (200, 150), (241, 321), (8, 8), (16, 16), (480, 640), (1, 1), (23, 9)):
    save(f'odd_{w}x{h}', photo(w, h, w * 7 + h), quality=60)
    save(f'odd444_{w}x{h}', photo(w, h, w * 7 + h), quality=60, subsampling=0)
save('gray', base.convert('L'), quality=50)
save('gray_odd', photo(100, 77, 5).convert('L'), quality=50)
save('flat', Image.new('RGB', (240, 320), (30, 60, 200)), quality=50)
save('black', Image.new('RGB', (240, 320), (0, 0, 0)), quality=20)
save('bw_text', Image.new('RGB', (240, 320), (255, 255, 255)), quality=50)
b = io.BytesIO(); base.save(b, 'JPEG', quality=50, progressive=True); open(f'{out}/progressive.jpg', 'wb').write(b.getvalue())
open(f'{out}/manifest.txt', 'w').write('\n'.join(manifest) + '\n'); print(len(manifest), 'files in', out)
