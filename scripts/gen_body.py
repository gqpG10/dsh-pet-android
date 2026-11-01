#!/usr/bin/env python3
# 逐动作测出宠物本体（alpha 非透明像素）的并集包围盒，用于归一化大小与锚点
import subprocess, json, os, sys
W, H = 160, 90          # 采样分辨率（= 640x360 的 1/4）
SRC = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'webm')
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'body.json')
TH = 8                  # alpha 阈值，滤掉编码噪点

res = []
files = sorted(f for f in os.listdir(SRC) if f.endswith('.webm'))
for fn in files:
    p = subprocess.run(['ffmpeg', '-v', 'error', '-c:v', 'libvpx-vp9', '-i', os.path.join(SRC, fn),
                        '-vf', 'alphaextract,scale=%d:%d' % (W, H),
                        '-f', 'rawvideo', '-pix_fmt', 'gray', '-'],
                       capture_output=True)
    buf = p.stdout
    n = len(buf) // (W * H)
    x0, y0, x1, y1 = W, H, -1, -1
    for k in range(n):
        base = k * W * H
        row = buf[base:base + W * H]
        for y in range(H):
            off = y * W
            line = row[off:off + W]
            cols = [x for x in range(W) if line[x] > TH]
            if cols:
                if cols[0] < x0: x0 = cols[0]
                if cols[-1] > x1: x1 = cols[-1]
                if y < y0: y0 = y
                if y > y1: y1 = y
    if x1 < 0:
        res.append([0, 0, 640, 360])
    else:
        res.append([x0 * 4, y0 * 4, (x1 - x0 + 1) * 4, (y1 - y0 + 1) * 4])
    sys.stderr.write('%s %s\n' % (fn, res[-1]))
json.dump(res, open(OUT, 'w'))
print('wrote', OUT, len(res))
