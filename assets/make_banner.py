#!/usr/bin/env python3
"""Render assets/banner.png.

Same layout and palette as the Margy banner: mint field, the app icon on the
left, wordmark and subtitle on the right.
"""

import pathlib

from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).parent

W, H = 900, 260
MINT = (0x8D, 0xD1, 0xB0)
INK = (0x1C, 0x2C, 0x24)
INK_SOFT = (0x30, 0x4A, 0x3C)
WHITE = (255, 255, 255)

BOLD = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
REGULAR = "/usr/share/fonts/noto/NotoSans-Regular.ttf"

WORDMARK = "TikTok SF"
SUBTITLE = "a TikTok mod for Android"


def logo(im, box):
    """The app icon, where the outlined glyph used to be."""
    size = box[2] - box[0]
    tile = Image.open(HERE.parent / "icon_out" / "margyt-1024.png").resize((size, size), Image.LANCZOS)
    im.paste(Image.new("RGB", (size, size), MINT), box[:2])
    im.paste(tile, box[:2], tile)


def main():
    im = Image.new("RGB", (W, H), MINT)
    d = ImageDraw.Draw(im)

    logo(im, (65, 32, 250, 217))

    wordmark = ImageFont.truetype(BOLD, 82)
    subtitle = ImageFont.truetype(REGULAR, 30)

    d.text((285, 74), WORDMARK, font=wordmark, fill=INK)
    d.text((288, 168), SUBTITLE, font=subtitle, fill=INK_SOFT)

    out = HERE / "banner.png"
    im.save(out)
    print("wrote", out, im.size)


if __name__ == "__main__":
    main()
