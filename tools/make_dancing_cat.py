"""Generates the Dancing Cat widget frames (black cat, purple neon rim, pink accents). Run: python3 tools/make_dancing_cat.py"""
from PIL import Image, ImageDraw, ImageFilter
import math, os
OUT = os.path.join(os.path.dirname(__file__), '..', 'plugins', 'widgets', 'res', 'drawable-nodpi') + os.sep
SS = 4; N = 288
INK = (10, 7, 18, 255); RIM = (140, 82, 255, 255); PINK = (255, 140, 220, 255); LAV = (201, 160, 255, 255)
PIV = (144, 266)

def S(v): return v * SS
def pts(p): return [(S(x), S(y)) for x, y in p]

def bez(p0, p1, p2, n=24):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0], (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in [i / n for i in range(n + 1)]]

def capsule(d, a, b, w, fill, rim=None, rw=2.5):
    if rim: d.line(pts([a, b]), fill=rim, width=int(S(w + rw * 2))); d.ellipse([S(a[0] - w / 2 - rw), S(a[1] - w / 2 - rw), S(a[0] + w / 2 + rw), S(a[1] + w / 2 + rw)], fill=rim); d.ellipse([S(b[0] - w / 2 - rw), S(b[1] - w / 2 - rw), S(b[0] + w / 2 + rw), S(b[1] + w / 2 + rw)], fill=rim)
    d.line(pts([a, b]), fill=fill, width=int(S(w))); d.ellipse([S(a[0] - w / 2), S(a[1] - w / 2), S(a[0] + w / 2), S(a[1] + w / 2)], fill=fill); d.ellipse([S(b[0] - w / 2), S(b[1] - w / 2), S(b[0] + w / 2), S(b[1] + w / 2)], fill=fill)

def ellipse(d, c, rx, ry, fill, rim=None, rw=2.5):
    if rim: d.ellipse([S(c[0] - rx - rw), S(c[1] - ry - rw), S(c[0] + rx + rw), S(c[1] + ry + rw)], fill=rim)
    d.ellipse([S(c[0] - rx), S(c[1] - ry), S(c[0] + rx), S(c[1] + ry)], fill=fill)

def poly(d, p, fill, rim=None, rw=2.5):
    if rim: d.line(pts(p + [p[0]]), fill=rim, width=int(S(rw * 2)), joint='curve')
    d.polygon(pts(p), fill=fill)

def note(d, x, y, s=1.0):
    ellipse(d, (x, y), 7 * s, 5 * s, PINK); d.line(pts([(x + 6 * s, y), (x + 6 * s, y - 24 * s)]), fill=PINK, width=int(S(3 * s)))
    d.polygon(pts([(x + 6 * s, y - 24 * s), (x + 18 * s, y - 16 * s), (x + 6 * s, y - 14 * s)]), fill=PINK)

ARM = {'up': (22, -52), 'mid': (40, -4), 'down': (16, 38)}

def cat(frame):
    dy, sway, la, ra, tail, happy, notes = frame
    base = Image.new('RGBA', (S(N), S(N)), (0, 0, 0, 0)); bd = ImageDraw.Draw(base)
    # legs + feet stay planted on the ground while the body bounces (legs stretch)
    for fx in (118, 170):
        capsule(bd, (fx, 232 + dy * 0.2), (fx, 262), 18, INK, RIM, 2.5)
        ellipse(bd, (fx + (-4 if fx < 144 else 4), 266), 21, 11, INK, RIM, 2.5)
    up = Image.new('RGBA', base.size, (0, 0, 0, 0)); d = ImageDraw.Draw(up)
    # tail (behind the body)
    tp = bez((186, 236 + dy), (250, 226 + dy + tail * 0.4), (226 + tail, 150 + dy), 28)
    d.line(pts(tp), fill=RIM, width=int(S(20)), joint='curve'); d.line(pts(tp), fill=INK, width=int(S(15)), joint='curve')
    ellipse(d, tp[-1], 9, 9, PINK)
    # body
    ellipse(d, (144, 206 + dy), 47, 54, INK, RIM, 2.5)
    ellipse(d, (144, 222 + dy), 26, 28, (28, 18, 48, 255))                                 # belly sheen
    # arms
    for s, st in ((-1, la), (1, ra)):
        sh = (144 + s * 40, 182 + dy); tx, ty = ARM[st]; pw = (sh[0] + s * tx, sh[1] + ty)
        capsule(d, sh, pw, 18, INK, RIM, 2.5)
        ellipse(d, pw, 14, 14, INK, RIM, 2.5)
        for k in (-5, 0, 5): ellipse(d, (pw[0] + k, pw[1] + 3), 2.2, 2.2, PINK)
    # ears, head
    for e in ([(88, 88), (97, 20), (140, 62)], [(200, 88), (191, 20), (148, 62)]): poly(d, [(x, y + dy) for x, y in e], INK, RIM, 2.5)
    for e in ([(100, 76), (104, 40), (127, 64)], [(188, 76), (184, 40), (161, 64)]): poly(d, [(x, y + dy) for x, y in e], (245, 130, 235, 255))
    ellipse(d, (144, 112 + dy), 68, 54, INK, RIM, 2.5)
    for x, h in ((124, 14), (144, 20), (164, 14)): poly(d, [(x - 8, 66 + dy), (x, 66 - h + dy), (x + 8, 66 + dy)], INK)
    # face
    for cx in (120, 168):
        if happy:
            d.arc([S(cx - 17), S(100 + dy), S(cx + 17), S(128 + dy)], 200, 340, fill=LAV, width=int(S(7)))
        else:
            ellipse(d, (cx, 112 + dy), 18, 21, (120, 70, 255, 90)); ellipse(d, (cx, 112 + dy), 13, 16, (176, 124, 255, 255)); ellipse(d, (cx, 118 + dy), 13, 10, (138, 84, 240, 255))
            ellipse(d, (cx, 112 + dy), 6, 12, INK); ellipse(d, (cx - 3, 105 + dy), 3, 3.5, (255, 255, 255, 255))
    poly(d, [(137, 127 + dy), (151, 127 + dy), (144, 135 + dy)], PINK)
    d.line(pts([(144, 135 + dy), (144, 141 + dy)]), fill=PINK, width=int(S(2)))
    d.arc([S(132), S(136 + dy), S(144), S(148 + dy)], 10, 170, fill=PINK, width=int(S(2))); d.arc([S(144), S(136 + dy), S(156), S(148 + dy)], 10, 170, fill=PINK, width=int(S(2)))
    if happy:
        for cx in (100, 188): ellipse(d, (cx, 132 + dy), 9, 5, (255, 120, 200, 80))
    for k, o in enumerate((-8, 2, 12)):
        d.line(pts([(98, 128 + o + dy), (66, 120 + o * 1.6 + dy)]), fill=(225, 190, 255, 255), width=int(S(1.6)))
        d.line(pts([(190, 128 + o + dy), (222, 120 + o * 1.6 + dy)]), fill=(225, 190, 255, 255), width=int(S(1.6)))
    up = up.rotate(sway, center=(S(PIV[0]), S(PIV[1])), resample=Image.BICUBIC)           # body sway around the feet
    base.alpha_composite(up)
    for (x, y, sc) in notes: note(ImageDraw.Draw(base), x, y, sc)
    # neon glow behind everything
    a = base.split()[3].filter(ImageFilter.GaussianBlur(S(7)))
    glow = Image.new('RGBA', base.size, (125, 70, 255, 0)); glow.putalpha(a.point(lambda v: int(v * 0.8)))
    out = Image.new('RGBA', base.size, (0, 0, 0, 0)); out.alpha_composite(glow); out.alpha_composite(base)
    return out.resize((N, N), Image.LANCZOS)

# (bounce dy, sway deg, left arm, right arm, tail swing, happy eyes, floating notes)
FRAMES = [
    (0, -4, 'up', 'down', -14, True, [(46, 70, 0.9), (246, 96, 0.7)]),
    (-12, -2, 'up', 'mid', 0, True, [(52, 56, 1.0), (240, 82, 0.8)]),
    (0, 0, 'mid', 'mid', 14, True, [(58, 44, 0.9), (236, 70, 0.9)]),
    (0, 4, 'down', 'up', 14, True, [(250, 70, 0.9), (50, 96, 0.7)]),
    (-12, 2, 'mid', 'up', 0, True, [(244, 56, 1.0), (56, 82, 0.8)]),
    (0, 0, 'mid', 'mid', -14, True, [(238, 44, 0.9), (60, 70, 0.9)]),
]
for i, f in enumerate(FRAMES): cat(f).save(OUT + f'meow_dance_{i}.png')
cat((0, 0, 'down', 'down', -6, False, [])).save(OUT + 'meow_dance_idle.png')

def eq(heights, dim=False):
    W, H = 300, 100; s = 3
    im = Image.new('RGBA', (W * s, H * s), (0, 0, 0, 0)); gl = Image.new('RGBA', im.size, (0, 0, 0, 0))
    bw, gap = 40, 20; x0 = (W - (5 * bw + 4 * gap)) / 2
    for i, h in enumerate(heights):
        x = (x0 + i * (bw + gap)) * s; bh = max(8, int(H * h)) * s; top = H * s - 4 * s - bh
        bar = Image.new('RGBA', (bw * s, bh), (0, 0, 0, 0)); px = bar.load()
        for y in range(bh):
            t = y / max(1, bh - 1)
            c = (int(242 * (1 - t) + 123 * t), int(140 * (1 - t) + 69 * t), int(240 * (1 - t) + 245 * t)) if not dim else (108, 85, 176)
            for xx in range(bw * s): px[xx, y] = c + ((255 if not dim else 150),)
        m = Image.new('L', bar.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, bar.width - 1, bar.height - 1], radius=bw * s // 2, fill=255)
        bar.putalpha(m); gl.alpha_composite(bar, (int(x), top)); im.alpha_composite(bar, (int(x), top))
    if not dim:
        g = gl.filter(ImageFilter.GaussianBlur(5 * s)); g.putalpha(g.split()[3].point(lambda v: int(v * 0.7)))
        out = Image.new('RGBA', im.size, (0, 0, 0, 0)); out.alpha_composite(g); out.alpha_composite(im); im = out
    return im.resize((W, H), Image.LANCZOS)
EQ = [(.35, .75, .5, .9, .45), (.6, .4, .85, .55, .8), (.85, .6, .4, .75, .55), (.5, .9, .65, .35, .7), (.75, .5, .9, .6, .4), (.4, .7, .55, .85, .6)]
for i, h in enumerate(EQ): eq(h).save(OUT + f'meow_eq_{i}.png')
eq((.18,) * 5, dim=True).save(OUT + 'meow_eq_idle.png')
print('frames written to', os.path.abspath(OUT))
