"""Store icon (512x512) and feature graphic (1024x500) for Google Play, drawn with PIL at 4x and downsampled."""
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "fastlane", "metadata", "android", "de-DE", "images")
os.makedirs(OUT, exist_ok=True)

BLUE_TOP = (42, 98, 153)
BLUE_BOTTOM = (20, 54, 88)
PAGE_L = (255, 255, 255)
PAGE_R = (226, 236, 247)
SPINE = (180, 200, 222)
ACCENT = (242, 169, 59)
CLOUD = (160, 202, 253)
FONT_B = r'C:\Windows\Fonts\seguisb.ttf'
FONT_R = r'C:\Windows\Fonts\segoeui.ttf'


def vgradient(w, h, top, bottom):
    img = Image.new('RGB', (w, h), top)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def draw_logo(img, cx, cy, size):
    """Cloud + open book + bookmark, centred at (cx, cy) inside a box of `size` px."""
    s = size / 100.0
    layer = Image.new('RGBA', img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    # cloud behind the book (soft, light blue)
    cloud = [(cx - 30 * s, cy - 22 * s, 16 * s), (cx - 10 * s, cy - 32 * s, 21 * s), (cx + 14 * s, cy - 27 * s, 18 * s), (cx + 31 * s, cy - 18 * s, 13 * s)]
    for x, y, r in cloud:
        d.ellipse([x - r, y - r, x + r, y + r], fill=CLOUD + (90,))
    d.rounded_rectangle([cx - 44 * s, cy - 22 * s, cx + 44 * s, cy - 6 * s], radius=8 * s, fill=CLOUD + (90,))
    # shadow under the book
    shadow = Image.new('RGBA', img.size, (0, 0, 0, 0))
    ds = ImageDraw.Draw(shadow)
    ds.ellipse([cx - 38 * s, cy + 26 * s, cx + 38 * s, cy + 36 * s], fill=(0, 0, 0, 90))
    shadow = shadow.filter(ImageFilter.GaussianBlur(4 * s))
    layer = Image.alpha_composite(layer, shadow)
    d = ImageDraw.Draw(layer)
    # open book: two pages slightly lifted at the outer edges
    top_y, bot_y = cy - 18 * s, cy + 30 * s
    left = [(cx - 2 * s, top_y + 4 * s), (cx - 40 * s, top_y - 2 * s), (cx - 40 * s, bot_y - 6 * s), (cx - 2 * s, bot_y)]
    right = [(cx + 2 * s, top_y + 4 * s), (cx + 40 * s, top_y - 2 * s), (cx + 40 * s, bot_y - 6 * s), (cx + 2 * s, bot_y)]
    d.polygon(left, fill=PAGE_L)
    d.polygon(right, fill=PAGE_R)
    d.rectangle([cx - 2 * s, top_y + 4 * s, cx + 2 * s, bot_y], fill=SPINE)
    # text lines on the pages
    for i in range(5):
        y = top_y + (10 + i * 7) * s
        d.line([(cx - 34 * s, y - 1 * s), (cx - 9 * s, y + 1.5 * s)], fill=(196, 210, 228), width=max(1, int(2.2 * s)))
        if i < 4:
            d.line([(cx + 9 * s, y + 1.5 * s), (cx + 34 * s, y - 1 * s)], fill=(186, 202, 222), width=max(1, int(2.2 * s)))
    # bookmark ribbon on the right page
    bx = cx + 24 * s
    d.polygon([(bx, top_y - 1 * s), (bx + 8 * s, top_y - 2 * s), (bx + 8 * s, top_y + 22 * s), (bx + 4 * s, top_y + 17 * s), (bx, top_y + 22 * s)], fill=ACCENT)
    img.alpha_composite(layer)


def icon(path, px=512):
    k = 4
    W = px * k
    img = vgradient(W, W, BLUE_TOP, BLUE_BOTTOM).convert('RGBA')
    draw_logo(img, W / 2, W / 2 + W * 0.03, W * 0.78)
    img = img.resize((px, px), Image.LANCZOS)
    img.save(path, 'PNG', optimize=True)


def feature(path, w=1024, h=500):
    k = 3
    W, H = w * k, h * k
    img = vgradient(W, H, BLUE_TOP, BLUE_BOTTOM).convert('RGBA')
    d = ImageDraw.Draw(img)
    # soft decorative circles
    deco = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    dd = ImageDraw.Draw(deco)
    dd.ellipse([W * 0.62, -H * 0.35, W * 1.15, H * 0.75], fill=(255, 255, 255, 18))
    dd.ellipse([-W * 0.12, H * 0.55, W * 0.25, H * 1.4], fill=(255, 255, 255, 12))
    img = Image.alpha_composite(img, deco)
    # logo tile
    tile = 150 * k
    tx, ty = 64 * k, (h * k - tile) / 2 - 70 * k
    t = Image.new('RGBA', (int(tile), int(tile)), (0, 0, 0, 0))
    tg = vgradient(int(tile), int(tile), (52, 112, 170), (28, 70, 110)).convert('RGBA')
    mask = Image.new('L', (int(tile), int(tile)), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, tile - 1, tile - 1], radius=34 * k, fill=255)
    t.paste(tg, (0, 0), mask)
    draw_logo(t, tile / 2, tile / 2 + tile * 0.03, tile * 0.8)
    img.alpha_composite(t, (int(tx), int(ty)))
    # texts
    d = ImageDraw.Draw(img)
    f_title = ImageFont.truetype(FONT_B, 58 * k)
    f_sub = ImageFont.truetype(FONT_R, 27 * k)
    f_small = ImageFont.truetype(FONT_R, 21 * k)
    x0 = 64 * k
    y0 = ty + tile + 26 * k
    d.text((x0, y0), 'Ebook Reader', font=f_title, fill=(255, 255, 255))
    d.text((x0, y0 + 74 * k), 'E-Books & Comics aus deiner Nextcloud', font=f_sub, fill=(214, 230, 250))
    d.text((x0, y0 + 112 * k), 'Offline lesen · Lesestand-Sync · Regale & Serien', font=f_small, fill=(170, 200, 235))
    # stylised phone with a library grid (abstract covers)
    pw, ph = 250 * k, 470 * k
    px, py = W - 64 * k - pw, 55 * k
    phone = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    dp = ImageDraw.Draw(phone)
    dp.rounded_rectangle([px + 10 * k, py + 14 * k, px + pw + 10 * k, py + ph + 14 * k], radius=36 * k, fill=(0, 0, 0, 70))
    phone = phone.filter(ImageFilter.GaussianBlur(10 * k))
    img = Image.alpha_composite(img, phone)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([px, py, px + pw, py + ph], radius=36 * k, fill=(18, 26, 36))
    sx, sy, sw, sh = px + 10 * k, py + 10 * k, pw - 20 * k, ph - 20 * k
    d.rounded_rectangle([sx, sy, sx + sw, sy + sh], radius=28 * k, fill=(248, 249, 255))
    # app bar + chips
    d.rounded_rectangle([sx, sy, sx + sw, sy + 52 * k], radius=28 * k, fill=(248, 249, 255))
    d.text((sx + 16 * k, sy + 14 * k), 'Bibliothek', font=ImageFont.truetype(FONT_B, 17 * k), fill=(31, 78, 121))
    for i, cw in enumerate((54, 62, 50)):
        cx0 = sx + 14 * k + sum((54, 62, 50)[:i]) * k + i * 8 * k
        d.rounded_rectangle([cx0, sy + 50 * k, cx0 + cw * k, sy + 72 * k], radius=11 * k, outline=(170, 185, 205), width=2 * k)
    # cover grid 3 x 3
    covers = [(231, 111, 81), (42, 157, 143), (233, 196, 106), (38, 70, 83), (244, 162, 97), (106, 76, 147),
              (25, 130, 196), (138, 201, 38), (255, 89, 94)]
    gx, gy = sx + 14 * k, sy + 86 * k
    cw_, ch_ = (sw - 28 * k - 2 * 10 * k) / 3, 0
    ch_ = cw_ * 1.5
    for i, col in enumerate(covers):
        r, c = divmod(i, 3)
        x = gx + c * (cw_ + 10 * k)
        y = gy + r * (ch_ + 26 * k)
        if y + ch_ > sy + sh - 10 * k:
            break
        d.rounded_rectangle([x, y, x + cw_, y + ch_], radius=6 * k, fill=col)
        d.rectangle([x + 6 * k, y + 10 * k, x + cw_ - 6 * k, y + 14 * k], fill=(255, 255, 255, 200))
        d.rectangle([x + 6 * k, y + 20 * k, x + cw_ * 0.6, y + 23 * k], fill=(255, 255, 255, 160))
        if i in (1, 4):  # reading progress bar
            d.rectangle([x, y + ch_ - 5 * k, x + cw_, y + ch_], fill=(210, 220, 235))
            d.rectangle([x, y + ch_ - 5 * k, x + cw_ * (0.42 if i == 1 else 0.77), y + ch_], fill=(31, 78, 121))
        d.rounded_rectangle([x, y + ch_ + 7 * k, x + cw_ * 0.85, y + ch_ + 13 * k], radius=3 * k, fill=(120, 135, 155))
    img = img.resize((w, h), Image.LANCZOS).convert('RGB')
    img.save(path, 'PNG', optimize=True)


icon(os.path.join(OUT, 'icon.png'))
feature(os.path.join(OUT, 'featureGraphic.png'))
for f in ('icon.png', 'featureGraphic.png'):
    p = os.path.join(OUT, f)
    im = Image.open(p)
    print(f, im.size, im.mode, round(os.path.getsize(p) / 1024), 'KB')
