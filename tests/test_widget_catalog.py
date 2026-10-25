"""Validate that every widget is reachable through the Android manifest/resources."""
from pathlib import Path
import re
import json
import hashlib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'
catalog = (ROOT / 'port/WeatherWidgetCatalog.java').read_text(encoding='utf-8')
providers = re.findall(r'(Weather\w*Provider)\.class', catalog)
assert len(providers) == len(set(providers)) == 25
for edition, package in [('normal', 'dev.weather.oneplus'), ('test', 'dev.weather.test')]:
    app = ET.parse(ROOT / 'manifests' / f'{edition}.xml').getroot().find('application')
    receivers = [r for r in app.findall('receiver') if any(
        a.get(A+'name') == 'android.appwidget.action.APPWIDGET_UPDATE'
        for a in r.findall('intent-filter/action'))]
    assert len(receivers) == 25, (edition, len(receivers))
    assert {r.get(A+'name').split('.')[-1] for r in receivers} == set(providers)
    previews = set()
    for receiver in receivers:
        name = receiver.get(A+'name').split('.')[-1]
        assert receiver.get(A+'exported') == 'false', name
        source = (ROOT / 'port' / f'{name}.java').read_text(encoding='utf-8')
        metadata = next(m for m in receiver.findall('meta-data')
                        if m.get(A+'name') == 'android.appwidget.provider')
        resource = metadata.get(A+'resource').removeprefix('@xml/')
        info = ET.parse(ROOT / 'port/res/xml' / f'{resource}.xml').getroot()
        assert info.get(A+'configure') == 'dev.weather.oneplus.WeatherWidgetConfigureActivity'
        assert info.get(A+'updatePeriodMillis') == '0'
        if name.startswith('WeatherOxygen'):
            assert int(re.search(r'return\s+(\d+)\s*;', source).group(1)) == providers.index(name), name
            preview = info.get(A+'previewImage').removeprefix('@drawable/')
            assets = [ROOT / 'port/res/drawable' / f'{preview}.{extension}' for extension in ('webp', 'png')]
            assert any(asset.is_file() and asset.stat().st_size > 0 for asset in assets), preview
            previews.add(preview)
            label = receiver.get(A+'label').removeprefix('@string/')
            for language in ['values', 'values-zh']:
                strings = ET.parse(ROOT / 'port/res' / language / 'weather_oxygen_strings.xml').getroot()
                assert any(s.get('name') == label and s.text for s in strings), (language, label)
    assert len(previews) == 17, 'Each Oxygen entry needs its own preview'
    database = next(p for p in app.findall('provider')
                    if p.get(A+'name') == 'dev.weather.oneplus.WeatherDatabaseProvider')
    assert database.get(A+'process') == ':weather_db' and database.get(A+'exported') == 'false'
    assert database.get(A+'authorities') == package + '.weatherdb'
inventory=json.loads((ROOT/'port/assets/oxygen_widget/source_manifest.json').read_text(encoding='utf-8'))
for item in inventory['resources']:
    actual=ROOT/'port'/item['destination']
    assert hashlib.sha256(actual.read_bytes()).hexdigest()==item['sha256'],actual
assert len(list((ROOT/'port/assets/oxygen_widget/oos_backgrounds').glob('*.webp')))==5
assert len(list((ROOT/'port/res/drawable').glob('weather_oxygen_oos_*.xml')))==4
print('PASS: 25 registered/configurable widgets per edition, 17 distinct Oxygen previews, original asset hashes; private DB isolation retained')
