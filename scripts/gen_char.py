#!/usr/bin/env python3
# 测每个动作里“角色本体”而不是“所有可见像素”：
#  - body.json：alpha 并集（窗口要贴着所有可见像素，气泡/道具也算）
#  - char.json：[脚底中心x, 脚底y, 高度中位数]（按角色连通块算，用来统一大小与锚点）
import subprocess, json, os, sys
import numpy as np
from scipy import ndimage
W, H = 160, 90
ROOT = os.path.join(os.path.dirname(__file__), '..')
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'webm')
OUT_BODY = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'body.json')
OUT_CHAR = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'char.json')
TH = 8

bodies, chars = [], []
files = sorted(f for f in os.listdir(SRC) if f.endswith('.webm'))
for fn in files:
    p = subprocess.run(['ffmpeg', '-v', 'error', '-c:v', 'libvpx-vp9',
                        '-i', os.path.join(SRC, fn),
                        '-vf', 'alphaextract,scale=%d:%d' % (W, H),
                        '-f', 'rawvideo', '-pix_fmt', 'gray', '-'],
                       capture_output=True)
    n = len(p.stdout) // (W * H)
    if n == 0:
        bodies.append([0, 0, 640, 360]); chars.append([320, 340, 284]); sys.stderr.write('%s EMPTY\n' % fn); continue
    frames = np.frombuffer(p.stdout[:n * W * H], np.uint8).reshape(n, H, W)
    mask_all = (frames > TH).any(axis=0)
    ys, xs = np.nonzero(mask_all)
    if len(xs) == 0:
        bodies.append([0, 0, 640, 360]); chars.append([320, 340, 284]); continue
    bodies.append([int(xs.min()) * 4, int(ys.min()) * 4,
                   int(xs.max() - xs.min() + 1) * 4, int(ys.max() - ys.min() + 1) * 4])

    hs, fx, fb = [], [], []
    for k in range(n):
        m = frames[k] > TH
        if not m.any(): continue
        lab, cnt = ndimage.label(m, structure=np.ones((3, 3)))
        if cnt == 0: continue
        sizes = ndimage.sum(m, lab, range(1, cnt + 1))
        big = int(np.argmax(sizes)) + 1
        yy, xx = np.nonzero(lab == big)
        h = yy.max() - yy.min() + 1
        # 脚底：连通块最下面 15% 的行，取 x 范围中点
        bottom = yy.max()
        band = yy >= bottom - max(1, int(h * 0.15))
        fx.append((xx[band].min() + xx[band].max()) / 2.0)
        fb.append(bottom)
        hs.append(h)
    if hs:
        chars.append([int(round(float(np.median(fx)) * 4)),
                      int(round((float(np.median(fb)) + 1) * 4)),
                      int(round(float(np.median(hs)) * 4))])
    else:
        chars.append([320, 340, 284])
    sys.stderr.write('%s body=%s char=%s\n' % (fn, bodies[-1], chars[-1]))
json.dump(bodies, open(OUT_BODY, 'w'))
json.dump(chars, open(OUT_CHAR, 'w'))
print('wrote', OUT_BODY, OUT_CHAR, len(bodies), len(chars))
