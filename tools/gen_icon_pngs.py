"""生成 NoAd 启动器图标的 PNG 位图（用于 mipmap-*dpi 的 legacy 资源）。

## 为什么需要这个脚本

Android 8.0（API 26）起使用自适应图标（XML 矢量），
但以下场景仍会读取 mipmap-*dpi 下的位图：
  - API < 26 的设备（本项目 minSdk=30，实际上不覆盖，但部分启动器
    与通知栏、任务切换器等系统组件仍可能取位图资源）
  - 部分第三方启动器不完整支持自适应图标

模板自带的 ic_launcher.webp 是 Android Studio 的绿色机器人图标，
必须替换，否则会出现「应用内是新图标、桌面上还是机器人」的割裂。

## 实现说明

无第三方图像库（环境无网络，无法安装 Pillow），因此：
  - 自己写 PNG 编码器（zlib 是标准库，PNG 的 chunk 结构简单）
  - 图形用解析式距离场 + 4x4 超采样光栅化，保证边缘平滑

直接按设计规格绘制，与 ic_launcher_foreground.xml 的几何保持一致：
  - 画布 108x108（自适应图标基准尺寸）
  - 方框外沿 28..80，环宽 7，外圆角 9 / 内圆角 2
  - 斜杠带宽 9，45 度，中心缺口半宽 6.5
"""

import math
import struct
import zlib
import os

# ---------- PNG 编码 ----------

def write_png(path, width, height, rgba_rows):
    """把逐行的 RGBA bytes 写成 PNG。"""
    raw = b"".join(b"\x00" + row for row in rgba_rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


# ---------- 几何工具 ----------

def rounded_rect_sdf(px, py, x0, y0, x1, y1, r):
    """圆角矩形的有符号距离。负值在内部。"""
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    hx, hy = (x1 - x0) / 2.0 - r, (y1 - y0) / 2.0 - r
    dx = abs(px - cx) - hx
    dy = abs(py - cy) - hy
    outside = math.hypot(max(dx, 0.0), max(dy, 0.0))
    inside = min(max(dx, dy), 0.0)
    return outside + inside - r


def segment_sdf(px, py, ax, ay, bx, by, half_w):
    """线段（带宽）的有符号距离。用于斜杠。"""
    vx, vy = bx - ax, by - ay
    wx, wy = px - ax, py - ay
    t = (wx * vx + wy * vy) / (vx * vx + vy * vy)
    t = max(0.0, min(1.0, t))
    cx, cy = ax + t * vx, ay + t * vy
    return math.hypot(px - cx, py - cy) - half_w


def lerp(a, b, t):
    return a + (b - a) * t


def mix(c1, c2, t):
    return tuple(lerp(c1[i], c2[i], t) for i in range(3))


# ---------- 设计参数（与矢量文件一致） ----------

BOX_OUT = 26.0
BOX_SIZE = 56.0         # 26..82，比斜杠端点内收更多，保证方框是主体
BOX_R = 10.0
STROKE = 7.0

# 斜杠
SLASH_W = 7.0           # 斜杠宽度（略窄于方框描边 7，避免抢走方框的视觉主导权）
GAP_HALF = 5.0          # 缺口半宽（沿斜杠方向度量）
# 斜杠端点：沿 45 度方向超出画布，由启动器遮罩裁切
SLASH_START = (-2.0, 110.0)
SLASH_END = (110.0, -2.0)

# 背景渐变端点色（对应 ic_launcher_background.xml）
BG_A = (0x0B, 0x3A, 0x2E)   # 左上 深青
BG_B = (0x0E, 0x5C, 0x46)   # 中   青绿
BG_C = (0x0A, 0x7D, 0x5E)   # 右下 翡翠

# 柔光
GLOW_CENTER = (82.0, 26.0)
GLOW_RADIUS = 72.0
GLOW_COLOR = (0x00, 0xD9, 0xA3)
GLOW_ALPHA = 0.30

COOL_CENTER = (18.0, 94.0)
COOL_RADIUS = 60.0
COOL_COLOR = (0x4D, 0xD0, 0xE1)
COOL_ALPHA = 0.20


def bg_color(x, y):
    """背景色：线性渐变 + 两处径向柔光。"""
    t = (x + y) / 216.0
    t = max(0.0, min(1.0, t))
    if t < 0.45:
        base = mix(BG_A, BG_B, t / 0.45)
    else:
        base = mix(BG_B, BG_C, (t - 0.45) / 0.55)

    # 暖光
    d = math.hypot(x - GLOW_CENTER[0], y - GLOW_CENTER[1])
    if d < GLOW_RADIUS:
        a = GLOW_ALPHA * (1.0 - d / GLOW_RADIUS) ** 2
        base = mix(base, GLOW_COLOR, a)

    # 冷光
    d = math.hypot(x - COOL_CENTER[0], y - COOL_CENTER[1])
    if d < COOL_RADIUS:
        a = COOL_ALPHA * (1.0 - d / COOL_RADIUS) ** 2
        base = mix(base, COOL_COLOR, a)

    return base


def slash_sdfs(px, py):
    """返回斜杠两段的 SDF。"""
    x0, y0 = SLASH_START
    x1, y1 = SLASH_END
    dx, dy = x1 - x0, y1 - y0
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    nx, ny = -uy, ux
    half = SLASH_W / 2.0
    mid = length / 2.0

    def pt(t, off):
        return (x0 + ux * t + nx * off, y0 + uy * t + ny * off)

    a0 = pt(0.0, 0.0)
    a1 = pt(mid - GAP_HALF, 0.0)
    b0 = pt(mid + GAP_HALF, 0.0)
    b1 = pt(length, 0.0)

    lo = segment_sdf(px, py, a0[0], a0[1], a1[0], a1[1], half)
    hi = segment_sdf(px, py, b0[0], b0[1], b1[0], b1[1], half)
    return lo, hi


def shade(x, y):
    """单个采样点的最终颜色，返回 **0..255** 区间的 RGB。"""
    r, g, b = bg_color(x, y)

    # --- 方框：外轮廓减内轮廓 ---
    outer = rounded_rect_sdf(x, y, BOX_OUT, BOX_OUT,
                             BOX_OUT + BOX_SIZE, BOX_OUT + BOX_SIZE, BOX_R)
    inner = rounded_rect_sdf(x, y, BOX_OUT + STROKE, BOX_OUT + STROKE,
                             BOX_OUT + BOX_SIZE - STROKE,
                             BOX_OUT + BOX_SIZE - STROKE, max(BOX_R - STROKE, 1.0))
    box_ring = max(outer, -inner)     # 环的 SDF

    # --- 斜杠 ---
    lo, hi = slash_sdfs(x, y)
    slash = min(lo, hi)

    # 方框与斜杠取并集（都是白色前景），因此用 min 合并 SDF
    glyph = min(box_ring, slash)

    if glyph <= 0.0:
        return (255, 255, 255)
    return (r, g, b)


def render(size, supersample=4):
    """渲染一张 size x size 的方形图标（RGB 不透明）。"""
    scale = 108.0 / size
    ss = supersample
    rows = []
    inv_ss = 1.0 / (ss * ss)

    for py in range(size):
        row = bytearray()
        for px in range(size):
            acc = [0.0, 0.0, 0.0]
            for sy in range(ss):
                for sx in range(ss):
                    x = (px + (sx + 0.5) / ss) * scale
                    y = (py + (sy + 0.5) / ss) * scale
                    c = shade(x, y)
                    acc[0] += c[0]
                    acc[1] += c[1]
                    acc[2] += c[2]
            # shade() 已返回 0..255，此处只做超采样平均，不再乘 255
            r = acc[0] * inv_ss
            g = acc[1] * inv_ss
            b = acc[2] * inv_ss

            row += bytes((
                max(0, min(255, int(r + 0.5))),
                max(0, min(255, int(g + 0.5))),
                max(0, min(255, int(b + 0.5))),
                255,
            ))
        rows.append(bytes(row))
    return rows


def apply_round_mask(rows, size, supersample=4):
    """对已渲染的方形图应用圆形遮罩（用于 ic_launcher_round.png）。

    独立于 [render] 处理，而不是在采样循环里判断，
    原因是圆形遮罩需要**跨像素**的抗锯齿计算，
    在逐像素循环里混入会让两件事互相干扰、难以单独调整。
    """
    scale = 108.0 / size
    center = (54.0, 54.0)
    radius = 54.0
    feather = 1.5 * scale
    ss = supersample

    out = []
    for py in range(size):
        row = bytearray(rows[py])
        for px in range(size):
            # 对像素的 ss x ss 子采样点求平均覆盖率
            covered = 0
            for sy in range(ss):
                for sx in range(ss):
                    x = (px + (sx + 0.5) / ss) * scale
                    y = (py + (sy + 0.5) / ss) * scale
                    d = math.hypot(x - center[0], y - center[1])
                    if d <= radius - feather:
                        covered += 1
                    elif d < radius:
                        t = (radius - d) / feather
                        if t > (sx + 0.5) / ss and t > (sy + 0.5) / ss:
                            covered += 1
            alpha = int(255 * covered / (ss * ss) + 0.5)
            i = px * 4
            row[i + 3] = alpha
        out.append(bytes(row))
    return out


# ---------- 主流程 ----------

# 各密度下启动器图标的像素尺寸
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BASE = "app/src/main/res"
PREVIEW = "reference"

for folder, size in DENSITIES.items():
    out_dir = os.path.join(BASE, folder)
    os.makedirs(out_dir, exist_ok=True)

    square = render(size, supersample=4)
    write_png(os.path.join(out_dir, "ic_launcher.png"), size, size, square)

    circular = apply_round_mask(square, size)
    write_png(os.path.join(out_dir, "ic_launcher_round.png"), size, size, circular)

    print(f"{folder}: {size}x{size} square + round")

# 生成大尺寸预览，便于人工核对设计与理解效果
os.makedirs(PREVIEW, exist_ok=True)
big = render(512, supersample=3)
write_png(os.path.join(PREVIEW, "preview_square.png"), 512, 512, big)
write_png(
    os.path.join(PREVIEW, "preview_round.png"),
    512, 512, apply_round_mask(big, 512, supersample=3),
)
print("preview: 512x512 square + round")

print("done")
