"""生成 NoAd 自适应图标的前景层矢量路径。

设计：
  - 方框（描边 = 外矩形 + 内矩形挖空，evenOdd 填充规则）
  - 斜杠（45 度，分上下两段，中间留缺口 => "被切断的广告位"）

用脚本生成而非手写，是为了保证：
  1. 斜杠两段的平行边严格共线（手写容易差几个单位，放大后能看到错位）
  2. 缺口宽度、圆角半径、斜杠端点都可参数化，便于后续微调
"""

import math

# ---------- 参数 ----------
# 方框
BOX_OUT = 26.0          # 外沿起始（对称，故 26..82）
BOX_SIZE = 56.0
BOX_R = 10.0            # 外圆角半径
STROKE = 7.0            # 方框描边厚度

# 斜杠
SLASH_W = 7.0           # 斜杠宽度（略窄于方框描边，避免抢走方框的视觉主导权）
GAP_HALF = 5.0          # 缺口半宽（沿斜杠方向度量）

# 斜杠端点：沿 45 度方向超出画布，由启动器遮罩裁切
SLASH_START = (-2.0, 110.0)
SLASH_END = (110.0, -2.0)


def arc(radius, sweep, large=0):
    return f"A{radius},{radius} 0 {large} {sweep}"


def rounded_rect(x0, y0, x1, y1, r, cw=True):
    """圆角矩形闭合路径。cw=True 顺时针，False 逆时针。

    ⚠️ 逆时针路径必须让 ArcTo 的 sweep-flag 为 0，
    且每段圆弧的起点/终点顺序与顺时针相反 —— 早期版本在这里出错，
    导致内挖空矩形变形。这里用「统一生成 + 反转」的方式保证对称正确性。
    """
    if cw:
        d = (
            f"M{x0 + r:.2f},{y0:.2f} "
            f"L{x1 - r:.2f},{y0:.2f} {arc(r, 1)} {x1:.2f},{y0 + r:.2f} "
            f"L{x1:.2f},{y1 - r:.2f} {arc(r, 1)} {x1 - r:.2f},{y1:.2f} "
            f"L{x0 + r:.2f},{y1:.2f} {arc(r, 1)} {x0:.2f},{y1 - r:.2f} "
            f"L{x0:.2f},{y0 + r:.2f} {arc(r, 1)} {x0 + r:.2f},{y0:.2f} Z"
        )
    else:
        d = (
            f"M{x0 + r:.2f},{y0:.2f} "
            f"L{x0:.2f},{y0 + r:.2f} {arc(r, 0)} {x0 + r:.2f},{y0:.2f} "
            f"L{x1 - r:.2f},{y0:.2f} {arc(r, 0)} {x1:.2f},{y0 + r:.2f} "
            f"L{x1:.2f},{y1 - r:.2f} {arc(r, 0)} {x1 - r:.2f},{y1:.2f} "
            f"L{x0 + r:.2f},{y1:.2f} {arc(r, 0)} {x0:.2f},{y1 - r:.2f} "
            f"L{x0:.2f},{y0 + r:.2f} {arc(r, 0)} {x0 + r:.2f},{y0:.2f} Z"
        )
    return d


def build_slash():
    """生成斜杠的两段（下半 + 上半），中间留缺口。"""
    x0, y0 = SLASH_START
    x1, y1 = SLASH_END
    dx, dy = x1 - x0, y1 - y0
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length     # 沿斜杠方向
    nx, ny = -uy, ux                      # 法向

    half = SLASH_W / 2.0
    mid = length / 2.0

    def pt(t, off):
        return (x0 + ux * t + nx * off, y0 + uy * t + ny * off)

    def quad(t0, t1):
        a, b = pt(t0, -half), pt(t1, -half)
        c, d = pt(t1, half), pt(t0, half)
        return (
            f"M{a[0]:.2f},{a[1]:.2f} L{b[0]:.2f},{b[1]:.2f} "
            f"L{c[0]:.2f},{c[1]:.2f} L{d[0]:.2f},{d[1]:.2f} Z"
        )

    lo = quad(0.0, mid - GAP_HALF)
    hi = quad(mid + GAP_HALF, length)
    gap_center = pt(mid, 0.0)
    return lo, hi, length, gap_center


box = rounded_rect(BOX_OUT, BOX_OUT, BOX_OUT + BOX_SIZE, BOX_OUT + BOX_SIZE, BOX_R, cw=True)
hole = rounded_rect(
    BOX_OUT + STROKE, BOX_OUT + STROKE,
    BOX_OUT + BOX_SIZE - STROKE, BOX_OUT + BOX_SIZE - STROKE,
    max(BOX_R - STROKE, 1.0), cw=False,
)
lo, hi, length, gc = build_slash()

print("BOX>>", f'{box} {hole}')
print()
print("SLASH_LO>>", lo)
print()
print("SLASH_HI>>", hi)
print()
print(f"CHECK slash_len={length:.1f} gap_center=({gc[0]:.2f},{gc[1]:.2f})")
inside = BOX_OUT < gc[0] < BOX_OUT + BOX_SIZE
print(f"CHECK gap_inside_box={inside}")
# 校验方框环宽是否均匀
r_in = max(BOX_R - STROKE, 1.0)
print(f"CHECK outer_edge={BOX_OUT} inner_edge={BOX_OUT + STROKE} "
      f"-> ring_width={STROKE}")
