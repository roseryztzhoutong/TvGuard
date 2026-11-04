# -*- coding: utf-8 -*-
"""
生成 Android TV 需要的 PNG 位图资源。
背景：多数 TV 桌面（Leanback / MIUI TV）不渲染 vector drawable，
      图标与 banner 必须是 PNG bitmap，否则桌面上看不到入口。
"""
import os
from PIL import Image, ImageDraw, ImageFont

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(BASE, "app", "src", "main", "res")

NAVY_TOP = (11, 27, 51)
NAVY_BOTTOM = (22, 50, 92)
GOLD = (250, 204, 21)
WHITE = (245, 248, 252)
GREY = (138, 158, 184)

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\Deng.ttf",
]


def load_font(size):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def vgradient(size, top, bottom):
    w, h = size
    img = Image.new("RGB", (1, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = (
            int(top[0] + (bottom[0] - top[0]) * t),
            int(top[1] + (bottom[1] - top[1]) * t),
            int(top[2] + (bottom[2] - top[2]) * t),
        )
    return img.resize((w, h), Image.BILINEAR).convert("RGBA")


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    d = ImageDraw.Draw(m)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def make_icon(px):
    """方形圆角图标：深蓝底 + 金色电视外框 + 屏内一个问号（答题）"""
    S = 4  # 超采样倍数，保证小尺寸边缘干净
    w = px * S
    img = vgradient((w, w), NAVY_TOP, NAVY_BOTTOM)
    d = ImageDraw.Draw(img)

    # 电视外框
    pad = int(w * 0.16)
    box = [pad, int(w * 0.22), w - pad, int(w * 0.78)]
    d.rounded_rectangle(box, radius=int(w * 0.07), outline=GOLD, width=max(2, int(w * 0.045)))

    # 屏幕内容：问号
    f = load_font(int(w * 0.30))
    txt = "?"
    bb = d.textbbox((0, 0), txt, font=f)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((w - tw) / 2 - bb[0], (w * 0.5) - th / 2 - bb[1]), txt, font=f, fill=GOLD)

    # 底座
    d.rounded_rectangle(
        [int(w * 0.40), int(w * 0.785), int(w * 0.60), int(w * 0.815)],
        radius=int(w * 0.015), fill=GOLD,
    )

    img = img.resize((px, px), Image.LANCZOS)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.paste(img, (0, 0), rounded_mask((px, px), int(px * 0.20)))
    return out


def make_banner(w, h):
    """TV 首页横幅：320x180 比例，深蓝底 + 金色标题"""
    img = vgradient((w, h), NAVY_TOP, NAVY_BOTTOM)
    d = ImageDraw.Draw(img)

    # 左侧竖条
    d.rectangle([0, 0, int(w * 0.022), h], fill=GOLD)

    f1 = load_font(int(h * 0.30))
    f2 = load_font(int(h * 0.115))

    title = "答题锁"
    bb = d.textbbox((0, 0), title, font=f1)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    tx = (w - tw) / 2 - bb[0]
    ty = h * 0.30 - bb[1]
    d.text((tx, ty), title, font=f1, fill=GOLD)

    sub = "看十分钟  答一道题"
    bb2 = d.textbbox((0, 0), sub, font=f2)
    sw = bb2[2] - bb2[0]
    d.text(((w - sw) / 2 - bb2[0], h * 0.66), sub, font=f2, fill=GREY)

    return img.convert("RGBA")


def save(img, rel_path):
    full = os.path.join(RES, rel_path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full, "PNG", optimize=True)
    print("  %-46s %dx%d  %d bytes" % (rel_path.replace("\\", "/"), img.width, img.height, os.path.getsize(full)))


print("生成启动图标 (mipmap-*/ic_launcher.png)")
for dens, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    save(make_icon(px), os.path.join("mipmap-%s" % dens, "ic_launcher.png"))

print("生成 TV banner (drawable-*/tv_banner.png)")
for dens, (w, h) in [("xhdpi", (320, 180)), ("xxhdpi", (480, 270))]:
    save(make_banner(w, h), os.path.join("drawable-%s" % dens, "tv_banner.png"))

# 根 drawable 也放一份，部分 ROM 只认根目录
save(make_banner(320, 180), os.path.join("drawable", "tv_banner.png"))

# 部分开发机需要 @mipmap/ic_launcher_round
for dens, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    save(make_icon(px), os.path.join("mipmap-%s" % dens, "ic_launcher_round.png"))

print("完成")
