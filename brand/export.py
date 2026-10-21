"""Renders every icon and banner from the SVG masters in brand/ with headless Chrome.
Run from the repo root:  python3 -I brand/export.py
Writes the phone app's icon set, the TV launcher's icon and banner, and the README/GitHub images."""
import os, subprocess, sys, tempfile, pathlib
from PIL import Image, ImageDraw

ROOT = pathlib.Path(os.path.dirname(os.path.abspath(sys.argv[0]))).parent
BRAND, PHONE, TV, DOCS = ROOT / "brand", ROOT / "phone/assets", ROOT / "launcher/res", ROOT / "docs"
FONTS = ROOT / "launcher/assets/fonts"
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
tmp = pathlib.Path(tempfile.mkdtemp())


def render(html, w, h, out, transparent=True):
    """A page of exactly w x h, screenshotted (at 1x) to out."""
    page = tmp / "p.html"
    page.write_text(f"""<!doctype html><html><head><meta charset="utf-8"><style>
@font-face {{ font-family: IS; src: url("{(FONTS / 'InstrumentSerif-Regular.ttf').as_uri()}"); }}
@font-face {{ font-family: FT; src: url("{(FONTS / 'Figtree.ttf').as_uri()}"); font-weight: 300 900; }}
html, body {{ margin: 0; width: {w}px; height: {h}px; overflow: hidden; background: transparent; }}
</style></head><body>{html}</body></html>""")
    shot = tmp / "s.png"
    subprocess.run([CHROME, "--headless=new", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--virtual-time-budget=3000", f"--window-size={w},{h}", f"--screenshot={shot}",
                    *(["--default-background-color=00000000"] if transparent else []), page.as_uri()],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    out.parent.mkdir(parents=True, exist_ok=True)
    Image.open(shot).convert("RGBA").crop((0, 0, w, h)).save(out)
    if ROOT in out.parents: print("wrote", out.relative_to(ROOT), f"{w}x{h}")


def svg(name, size, scale=1.0):
    """An SVG master at size px, its art scaled about the centre (adaptive icons keep to the safe zone)."""
    src = (BRAND / name).as_uri()
    inset = size * (1 - scale) / 2
    return f'<img src="{src}" style="position:absolute;left:{inset}px;top:{inset}px;width:{size*scale}px;height:{size*scale}px">'


def rounded(src, out, size, radius=0.225):
    im = Image.open(src).convert("RGBA").resize((size, size), Image.LANCZOS)
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * radius), fill=255)
    im.putalpha(m)
    im.save(out)
    print("wrote", out.relative_to(ROOT), f"{size}x{size} rounded")




def banner(w, h, out):
    """Fire TV apps-row banner: the sky, the glass flame, the wordmark."""
    sky = f'<div style="position:absolute;inset:0;background:radial-gradient(70% 90% at 12% 0%,#2E3A8C,transparent 70%),radial-gradient(60% 90% at 100% 50%,rgba(110,63,184,.85),transparent 70%),radial-gradient(55% 80% at 0% 100%,rgba(27,111,138,.7),transparent 70%),#070B1F"></div>'
    mark = f'<img src="{(BRAND / "fireglass-mark.svg").as_uri()}" style="position:absolute;left:{w*.03}px;top:{(h-h*.92)/2}px;width:{h*.92}px;height:{h*.92}px">'
    word = f'<div style="position:absolute;left:{w*.36}px;top:0;bottom:0;display:flex;align-items:center;font-family:IS;font-size:{h*.30}px;color:#fff;line-height:1">Fireglass</div>'
    render(sky + mark + word, w, h, out, transparent=False)


# phone (Expo): full icon, adaptive layers, monochrome, splash, web favicon
render(svg("fireglass-icon.svg", 1024), 1024, 1024, PHONE / "icon.png", transparent=False)
render(f'<div style="position:absolute;inset:0;background:radial-gradient(60% 60% at 18% 12%,#2E3A8C,transparent 75%),radial-gradient(55% 55% at 96% 40%,rgba(110,63,184,.9),transparent 75%),radial-gradient(50% 50% at 10% 100%,rgba(27,111,138,.75),transparent 75%),#070B1F"></div>',
       1024, 1024, PHONE / "android-icon-background.png", transparent=False)
render(svg("fireglass-mark.svg", 1024, 0.62), 1024, 1024, PHONE / "android-icon-foreground.png")
render(svg("fireglass-mono.svg", 1024, 0.62), 1024, 1024, PHONE / "android-icon-monochrome.png")
render(svg("fireglass-mark.svg", 512), 512, 512, PHONE / "splash-icon.png")
render(svg("fireglass-favicon.svg", 48), 48, 48, PHONE / "favicon.png", transparent=False)

# TV launcher: icon and the 16:9 banner Fire TV shows in its apps row
render(svg("fireglass-icon.svg", 192), 192, 192, TV / "drawable-xxxhdpi/fg_icon.png", transparent=False)
render(svg("fireglass-icon.svg", 96), 96, 96, TV / "drawable-xhdpi/fg_icon.png", transparent=False)
banner(320, 180, TV / "drawable-xhdpi/fg_banner.png")
banner(640, 360, TV / "drawable-xxxhdpi/fg_banner.png")

# README icon (rounded, transparent corners) and the GitHub social preview card
render(svg("fireglass-icon.svg", 1024), 1024, 1024, tmp / "full.png", transparent=False)
rounded(tmp / "full.png", DOCS / "fireglass-icon.png", 512)
social = (f'<div style="position:absolute;inset:0;background:radial-gradient(60% 80% at 10% 0%,#2E3A8C,transparent 70%),radial-gradient(60% 80% at 100% 50%,rgba(110,63,184,.85),transparent 70%),radial-gradient(50% 70% at 0% 100%,rgba(27,111,138,.7),transparent 70%),#070B1F"></div>'
          f'<img src="{(BRAND / "fireglass-mark.svg").as_uri()}" style="position:absolute;left:70px;top:70px;width:500px;height:500px">'
          '<div style="position:absolute;left:560px;right:70px;top:0;bottom:0;display:flex;flex-direction:column;justify-content:center;color:#fff">'
          '<div style="font-family:IS;font-size:120px;line-height:1">Fireglass</div>'
          '<div style="font-family:FT;font-weight:500;font-size:32px;line-height:1.35;margin-top:22px;color:rgba(255,255,255,.86)">A faster home for your Fire TV, and a remote on your phone.</div>'
          '<div style="font-family:FT;font-weight:600;font-size:26px;margin-top:28px;color:#A9B8FF">github.com/bhaweshverma50/fireglass</div></div>')
render(social, 1280, 640, DOCS / "social-preview.png", transparent=False)

# README header: a rounded night-sky card (transparent corners, so it sits on GitHub's light and dark themes)
pill = ('<span style="display:inline-flex;align-items:center;height:56px;padding:0 26px;border-radius:28px;'
        'background:rgba(255,255,255,.10);border:2px solid rgba(169,184,255,.28);font-family:FT;font-weight:600;font-size:26px;color:#E6E9FF">{}</span>')
header = (f'<div style="position:absolute;inset:0;border-radius:48px;overflow:hidden;background:radial-gradient(55% 90% at 8% 0%,#2E3A8C,transparent 70%),radial-gradient(50% 90% at 100% 45%,rgba(110,63,184,.85),transparent 70%),radial-gradient(45% 80% at 0% 100%,rgba(27,111,138,.7),transparent 70%),#070B1F">'
          f'<img src="{(BRAND / "fireglass-mark.svg").as_uri()}" style="position:absolute;left:60px;top:40px;width:520px;height:520px">'
          '<div style="position:absolute;left:600px;right:80px;top:0;bottom:0;display:flex;flex-direction:column;justify-content:center;color:#fff">'
          '<div style="font-family:IS;font-size:190px;line-height:.95;letter-spacing:-2px">Fireglass</div>'
          '<div style="font-family:FT;font-weight:500;font-size:36px;line-height:1.35;margin-top:22px;color:rgba(255,255,255,.88);"><div>A fast home screen for the Fire TV Stick,</div><div>and a remote on your phone.</div></div>'
          '<div style="display:flex;gap:16px;margin-top:38px">' + "".join(pill.format(t) for t in ["Fire TV Stick", "iPhone", "Android", "No root"]) + '</div>'
          '</div></div>')
render(header, 1760, 600, DOCS / "readme-header.png")
