"""Synthetic pictures of the kind a note gets: screenshots, slides, a photographed page.

Real user images cannot be checked into the repository, so the corpus is generated. Each sample
carries the exact lines it was drawn from, which is what makes a character error rate meaningful.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_SANS: str = "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Regular.ttf"
FONT_SANS_BOLD: str = "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Bold.ttf"
FONT_SERIF: str = "/usr/share/fonts/liberation-serif-fonts/LiberationSerif-Regular.ttf"
FONT_MONO: str = "/usr/share/fonts/liberation-mono-fonts/LiberationMono-Regular.ttf"


@dataclass
class Sample:
    """One test picture and the text it was drawn from."""

    name: str
    image: Image.Image
    lines: list[str] = field(default_factory=list)

    @property
    def text(self) -> str:
        return "\n".join(self.lines)


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


def screenshot_light() -> Sample:
    """A web page or document screenshot: dark text, white ground, one column."""
    lines: list[str] = [
        "Release notes for version 3.2",
        "Fixed a crash when importing large notebooks.",
        "Search now covers table cells and page titles.",
        "Stylus pressure is smoothed on Lenovo pens.",
        "Known issue: export drops empty sections.",
    ]
    image: Image.Image = Image.new("RGB", (900, 420), (255, 255, 255))
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(image)
    heading = font(FONT_SANS_BOLD, 34)
    body = font(FONT_SANS, 26)
    draw.text((48, 40), lines[0], font=heading, fill=(20, 20, 24))
    for index, line in enumerate(lines[1:]):
        draw.text((48, 120 + index * 62), line, font=body, fill=(48, 48, 54))
    return Sample("screenshot_light", image, lines)


def screenshot_dark() -> Sample:
    """The same shape inverted, because a pasted screenshot is as often dark as light."""
    lines: list[str] = [
        "Terminal output",
        "gradle assembleDebug",
        "BUILD SUCCESSFUL in 42s",
        "142 actionable tasks: 30 executed",
    ]
    image: Image.Image = Image.new("RGB", (760, 300), (18, 18, 22))
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(image)
    heading = font(FONT_SANS_BOLD, 28)
    body = font(FONT_MONO, 24)
    draw.text((40, 32), lines[0], font=heading, fill=(232, 232, 238))
    for index, line in enumerate(lines[1:]):
        draw.text((40, 100 + index * 58), line, font=body, fill=(180, 220, 180))
    return Sample("screenshot_dark", image, lines)


def slide() -> Sample:
    """A lecture slide: a big title and short bullets, plenty of empty space."""
    lines: list[str] = [
        "Gradient Descent",
        "Follow the negative gradient",
        "Step size controls stability",
        "Momentum smooths the path",
    ]
    image: Image.Image = Image.new("RGB", (1000, 560), (250, 248, 244))
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 1000, 120), fill=(28, 60, 96))
    draw.text((56, 40), lines[0], font=font(FONT_SANS_BOLD, 44), fill=(255, 255, 255))
    body = font(FONT_SANS, 32)
    for index, line in enumerate(lines[1:]):
        y: int = 190 + index * 90
        draw.ellipse((60, y + 12, 76, y + 28), fill=(28, 60, 96))
        draw.text((100, y), line, font=body, fill=(34, 34, 40))
    return Sample("slide", image, lines)


def photographed_page(angle: float = -3.5) -> Sample:
    """A phone photo of paper: rotated, unevenly lit and slightly soft."""
    lines: list[str] = [
        "Meeting notes",
        "Ship the search panel this week",
        "Ask about the tablet budget",
        "Renew the signing certificate",
    ]
    page: Image.Image = Image.new("RGB", (900, 500), (252, 250, 246))
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(page)
    draw.text((60, 44), lines[0], font=font(FONT_SERIF, 40), fill=(30, 28, 26))
    body = font(FONT_SERIF, 30)
    for index, line in enumerate(lines[1:]):
        draw.text((60, 140 + index * 80), line, font=body, fill=(40, 38, 36))

    rotated: Image.Image = page.rotate(angle, expand=True, fillcolor=(120, 118, 112))
    shaded: Image.Image = Image.new("L", rotated.size, 0)
    shade_draw: ImageDraw.ImageDraw = ImageDraw.Draw(shaded)
    for x in range(0, rotated.size[0], 8):
        level: int = int(60 * math.sin(math.pi * x / rotated.size[0]))
        shade_draw.rectangle((x, 0, x + 8, rotated.size[1]), fill=max(0, level))
    lit: Image.Image = Image.composite(
        Image.new("RGB", rotated.size, (255, 255, 255)), rotated, shaded.point(lambda v: v // 3)
    )
    return Sample("photographed_page", lit.filter(ImageFilter.GaussianBlur(0.6)), lines)


def small_thumbnail() -> Sample:
    """Deliberately too small to read: the pipeline must return nothing, not nonsense."""
    image: Image.Image = Image.new("RGB", (120, 60), (240, 240, 240))
    ImageDraw.Draw(image).text((8, 20), "tiny", font=font(FONT_SANS, 12), fill=(90, 90, 90))
    return Sample("small_thumbnail", image, ["tiny"])


def photograph_no_text() -> Sample:
    """A picture with no writing in it at all — the common case, and it must cost little."""
    image: Image.Image = Image.new("RGB", (800, 600), (110, 150, 190))
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(image)
    for index in range(14):
        radius: int = 30 + index * 17
        draw.ellipse(
            (400 - radius, 470 - radius // 2, 400 + radius, 470 + radius // 2),
            outline=(90 + index * 8, 130 + index * 6, 70 + index * 10),
            width=9,
        )
    return Sample("photograph_no_text", image.filter(ImageFilter.GaussianBlur(1.2)), [])


def corpus() -> list[Sample]:
    return [
        screenshot_light(),
        screenshot_dark(),
        slide(),
        photographed_page(),
        small_thumbnail(),
        photograph_no_text(),
    ]
