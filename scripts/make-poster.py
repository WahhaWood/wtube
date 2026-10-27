#!/usr/bin/env python3
"""Builds a README poster: gradient, title, phones with captions (the look of docs/screenshots/*.png).

Screenshots are raw 1080x2424 phone captures. Take them on a phone with demo mode on, so the status bar reads 10:00 / 100 %:
  adb shell settings put global sysui_demo_allowed 1
  adb shell am broadcast -a com.android.systemui.demo -e command enter
  adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1000
  adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
Notification icons that demo mode leaves next to the clock are painted over (MASK).

Usage: scripts/make-poster.py <spec.json> <out.png>
spec.json: {"title": "...", "subtitle": "...", "phones": [{"image": "raw.png", "title": "...", "caption": "..."}]}
Image paths are relative to the spec file.
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1800, 1150
FONT = Path(__file__).resolve().parent.parent / "app/src/main/res/font/inter.ttf"
PHONE_W, PHONE_H, BEZEL, RADIUS = 320, 700, 6, 46
PHONE_TOP = 230
# Where demo mode leaves notification icons, in screenshot pixels (x1, y1, x2, y2), and where to sample the bar colour.
MASK, MASK_SAMPLE = (190, 50, 330, 115), (360, 85)


def font(size, weight):
    f = ImageFont.truetype(str(FONT), size)
    f.set_variation_by_axes([min(32, max(14, size)), weight])
    return f


def background():
    img = Image.new("RGB", (W, H), (12, 18, 46))
    for center, radius, color in [((0, 0), 1100, (44, 92, 160)), ((W, H), 1000, (74, 56, 142)), ((W, 0), 700, (30, 24, 92))]:
        glow = Image.new("RGB", (W, H), color)
        mask = Image.new("L", (W, H), 0)
        ImageDraw.Draw(mask).ellipse((center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius), fill=255)
        mask = mask.filter(ImageFilter.GaussianBlur(radius / 2.2))
        img.paste(glow, (0, 0), mask.point(lambda v: int(v * 0.85)))
    return img


def screen(path):
    img = Image.open(path).convert("RGB")
    img.paste(img.getpixel(MASK_SAMPLE), MASK)
    w = PHONE_W - 2 * BEZEL
    h = PHONE_H - 2 * BEZEL
    # Fill the screen width, cut from the bottom if the capture is taller than the phone.
    img = img.resize((w, round(img.height * w / img.width)), Image.LANCZOS).crop((0, 0, w, h))
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), RADIUS - BEZEL, fill=255)
    return img, mask


def phone(poster, path, x):
    shadow = Image.new("RGBA", (PHONE_W + 80, PHONE_H + 80), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((40, 50, PHONE_W + 40, PHONE_H + 50), RADIUS, fill=(0, 0, 0, 110))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    poster.paste(shadow, (x - 40, PHONE_TOP - 40), shadow)
    d = ImageDraw.Draw(poster)
    d.rounded_rectangle((x, PHONE_TOP, x + PHONE_W, PHONE_TOP + PHONE_H), RADIUS, fill=(8, 10, 14), outline=(52, 58, 72), width=2)
    img, mask = screen(path)
    poster.paste(img, (x + BEZEL, PHONE_TOP + BEZEL), mask)


def centered(d, cx, y, text, fnt, fill):
    d.text((cx, y), text, font=fnt, fill=fill, anchor="mt")


def main(spec_path, out):
    spec = json.loads(Path(spec_path).read_text(encoding="utf-8"))
    base = Path(spec_path).parent
    poster = background()
    d = ImageDraw.Draw(poster)
    centered(d, W // 2, 70, spec["title"], font(66, 700), (255, 255, 255))
    centered(d, W // 2, 150, spec["subtitle"], font(29, 400), (182, 188, 232))
    phones = spec["phones"]
    gap = (W - PHONE_W * len(phones)) // (len(phones) + 1)
    for i, p in enumerate(phones):
        x = gap + i * (PHONE_W + gap)
        phone(poster, base / p["image"], x)
        d = ImageDraw.Draw(poster)
        cx = x + PHONE_W // 2
        centered(d, cx, 965, p["title"], font(35, 600), (255, 255, 255))
        for n, line in enumerate(p["caption"].split("\n")):
            centered(d, cx, 1022 + n * 30, line, font(22, 400), (182, 188, 232))
    poster.save(out, optimize=True)


if __name__ == "__main__":
    main(*sys.argv[1:3])
