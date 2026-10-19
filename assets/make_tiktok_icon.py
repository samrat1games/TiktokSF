#!/usr/bin/env python3
"""Draw every picture of the app icon from `assets/tiktok_logo.png`.

    python3 assets/make_tiktok_icon.py

The logo is cut into its three colours -- the cyan copy, the red copy and the
white note on top -- and each is laid out again on the canvas an adaptive icon
uses, so the layers can be traced to vectors (`assets/make_logo_vector.py`)
and so the bitmaps, the legacy densities and the Play icon all come from the
same drawing. It writes:

    icon_out/margyt-1024.png, margyt-1024-glyph.png, play/*, mipmap-*/*
    site/margyt.png
    assets/ui/glyph_{cyan,red,white}.png   the layers, for the tracer

and prints the vector paths to paste into `margyt/artwork.py`.
"""
import os
import sys

import numpy
from scipy import ndimage
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

SOURCE = os.path.join(ROOT, "assets", "tiktok_logo.png")
LAYERS = os.path.join(ROOT, "assets", "ui")

BLACK = (0, 0, 0)
CYAN = (0x25, 0xF4, 0xEE)
RED = (0xFE, 0x2C, 0x55)
WHITE = (255, 255, 255)

BIG = 1600
#: height of the logo in the 108-unit adaptive square, and on a full-bleed icon
ADAPTIVE = 0.52
FULL = 0.66


def whole(mask):
    """Only the big pieces of a mask: a hairline of stray pixels is not the logo."""
    labels, count = ndimage.label(mask)
    if count == 0:
        return mask
    sizes = ndimage.sum(mask, labels, range(1, count + 1))
    keep = [i + 1 for i, size in enumerate(sizes) if size > sizes.max() * 0.02]
    return numpy.isin(labels, keep)


def masks():
    """The three layers as soft masks on a BIG square, the logo's own box."""
    logo = Image.open(SOURCE).convert("RGBA")
    flat = Image.new("RGBA", logo.size, BLACK + (255,))
    flat.alpha_composite(logo)
    rgb = numpy.asarray(flat.convert("RGB").resize((BIG, BIG), Image.LANCZOS), dtype=numpy.float64)
    palette = numpy.array([BLACK, CYAN, RED, WHITE], dtype=numpy.float64)
    nearest = numpy.argmin(((rgb[:, :, None, :] - palette[None, None]) ** 2).sum(3), axis=2)
    white, cyan, red = whole(nearest == 3), nearest == 1, nearest == 2
    # the source has a few stray pixels in its dark corners that read as red;
    # the logo is whatever is near the white note
    ys, xs = numpy.nonzero(white)
    pad = BIG // 20
    near = numpy.zeros_like(white)
    near[max(0, ys.min() - pad):ys.max() + pad, max(0, xs.min() - pad):xs.max() + pad] = True
    cyan, red = cyan & near, red & near
    cyan, red = whole(cyan), whole(red)
    out = {}
    for name, mask in (("cyan", cyan | white), ("red", red | white), ("white", white)):
        image = Image.fromarray((mask * 255).astype(numpy.uint8)).filter(ImageFilter.GaussianBlur(2.2))
        out[name] = image
    return out


def box(layers):
    union = numpy.asarray(layers["cyan"]) | numpy.asarray(layers["red"])
    ys, xs = numpy.nonzero(union > 127)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def place(layers, size, fraction):
    """Each layer, scaled so the logo is `fraction` of the square, centred."""
    left, top, right, bottom = box(layers)
    scale = size * fraction / (bottom - top)
    width, height = (right - left) * scale, (bottom - top) * scale
    at = ((size - width) / 2 - left * scale, (size - height) / 2 - top * scale)
    placed = {}
    for name, mask in layers.items():
        big = mask.resize((round(BIG * scale), round(BIG * scale)), Image.LANCZOS)
        canvas = Image.new("L", (size, size), 0)
        canvas.paste(big, (round(at[0]), round(at[1])))
        placed[name] = canvas
    return placed


def paint(placed, background, size):
    """The layers over a background, which is None for a picture with no ground."""
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0) if background is None else background + (255,))
    for name, colour in (("cyan", CYAN), ("red", RED), ("white", WHITE)):
        layer = Image.new("RGBA", (size, size), colour + (255,))
        layer.putalpha(placed[name])
        image.alpha_composite(layer)
    return image


def shape(image, kind):
    """A square cut to a rounded square or a circle, softly."""
    size = image.width
    mask = Image.new("L", (size * 4, size * 4), 0)
    draw = ImageDraw.Draw(mask)
    if kind == "circle":
        draw.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    else:
        draw.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=size * 4 * 0.18, fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = image.copy()
    alpha = numpy.minimum(numpy.asarray(out.getchannel("A")), numpy.asarray(mask))
    out.putalpha(Image.fromarray(alpha))
    return out


def save(image, *parts):
    path = os.path.join(ROOT, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, optimize=True)


def main():
    layers = masks()

    # the layers the vector is traced from: the 1024 square an adaptive icon uses
    adaptive = place(layers, 1024, ADAPTIVE)
    for name, mask in adaptive.items():
        alpha = Image.new("RGBA", (1024, 1024), WHITE + (0,))
        alpha.putalpha(mask)
        save(alpha, "assets", "ui", "glyph_%s.png" % name)
    save(paint(adaptive, None, 1024), "icon_out", "margyt-1024-glyph.png")

    full = place(layers, 1024, FULL)
    icon = paint(full, BLACK, 1024)
    rounded = shape(icon, "round-square")
    save(rounded, "icon_out", "margyt-1024.png")
    save(rounded, "site", "margyt.png")

    big512 = paint(place(layers, 512, FULL), BLACK, 512)
    save(shape(big512, "round-square"), "icon_out", "play", "ic_launcher-512.png")
    save(shape(big512, "circle"), "icon_out", "play", "ic_launcher-512-round.png")

    for density, scale in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        legacy, layer = round(48 * scale), round(108 * scale)
        small = place(layers, legacy * 4, FULL)
        flat = paint(small, BLACK, legacy * 4).resize((legacy, legacy), Image.LANCZOS)
        folder = "mipmap-" + density
        save(flat, "icon_out", folder, "ic_launcher.png")
        save(shape(flat, "circle"), "icon_out", folder, "ic_launcher_round.png")
        front = paint(place(layers, layer * 2, ADAPTIVE), None, layer * 2).resize((layer, layer), Image.LANCZOS)
        save(front, "icon_out", folder, "ic_launcher_foreground.png")
        save(Image.new("RGBA", (layer, layer), BLACK + (255,)), "icon_out", folder, "ic_launcher_background.png")

    import make_logo_vector as trace
    print("CYAN", trace.trace(os.path.join(LAYERS, "glyph_cyan.png")))
    print("RED", trace.trace(os.path.join(LAYERS, "glyph_red.png")))
    print("WHITE", trace.trace(os.path.join(LAYERS, "glyph_white.png")))


if __name__ == "__main__":
    main()
