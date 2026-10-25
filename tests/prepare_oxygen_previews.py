"""Create picker previews only from the device-rendered, synthetic test fixture."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
renders = ROOT / '.build/oxygen-widget-preview/rendered/files/renders'
styles = [(8, ''), (9, 'quick_small'), (10, 'quick_medium'), (11, 'quick_large'),
          (12, 'dragonfly'), (13, 'seedling_strip'), (14, 'seedling_square'),
          (15, 'glass_strip'), (16, 'adaptive'), (17, 'temperature'), (18, 'current'),
          (19, 'uv'), (20, 'sunrise'), (21, 'rpk_micro'), (22, 'rpk_strip'),
          (23, 'rpk_today'), (24, 'quick_small_oos')]
contact = Image.new('RGB', (1680, 2520), '#edf1f5')
draw = ImageDraw.Draw(contact)
font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf', 20)
for index, (style, key) in enumerate(styles):
    paths = list(renders.glob(f'style-{style}-*.png'))
    source = max(paths, key=lambda p: Image.open(p).height)
    if 17 <= style <= 21:
        source = renders / f'style-{style}-192x192.png'
    with Image.open(source) as original:
        art = original.convert('RGBA')
    preview = art.copy()
    preview.thumbnail((720, 720), Image.Resampling.LANCZOS)
    stem = 'weather_oxygen' + ('_' + key if key else '') + '_preview'
    preview.save(ROOT / 'port/res/drawable' / f'{stem}.webp', 'WEBP', quality=95, method=6)
    art.thumbnail((390, 370), Image.Resampling.LANCZOS)
    x, y = (index % 4)*420, (index // 4)*420
    draw.text((x+12, y+12), f'{style}: {key or "today"}', font=font, fill='#202020')
    contact.paste(art, (x+(420-art.width)//2, y+42+(370-art.height)//2), art)
extra = Image.open(renders / 'style-16-450x180.png').convert('RGBA')
contact.paste(extra, (18, 2220), extra)
draw.text((18, 2170), '16: adaptive strip', font=font, fill='#202020')
contact.save(ROOT / '.build/oxygen-widget-preview/contact-fixed.png')
print('Prepared 17 individual Oxygen previews from synthetic fixture renders')
