#!/usr/bin/env python3
"""Small ADB UI test helper: inspect actual bounds; never assumes a stale layout."""
import argparse, os, re, subprocess, tempfile, time, xml.etree.ElementTree as ET
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--device', required=True)
p.add_argument('--package', default='com.tanuki.modern')
p.add_argument('--tap')
p.add_argument('--system-dialog', action='store_true')
a = p.parse_args()
adb = Path(os.environ.get('ANDROID_SDK_ROOT', Path.home() / 'Library/Android/sdk')) / 'platform-tools/adb'
project = Path(__file__).resolve().parent.parent
out = project / '.build/logs/ui-current.xml'
remote = '/sdcard/tanuki-test-ui-%d.xml' % int(time.time() * 1000)
def call(*args):
    # ADB descendants can retain inherited pipe descriptors on macOS. A regular
    # temporary output file avoids waiting for an unrelated descendant's EOF.
    with tempfile.TemporaryFile(mode='w+') as output:
        subprocess.run([str(adb), '-s', a.device, *args], check=True, stdin=subprocess.DEVNULL,
                       stdout=output, stderr=subprocess.STDOUT, timeout=15)
        output.seek(0)
        return output.read()
for attempt in range(3):
    result = call('shell', 'uiautomator', 'dump', remote)
    if 'dumped to' not in result:
        time.sleep(.3)
        continue
    call('pull', remote, str(out))
    root = ET.parse(out)
    nodes = list(root.iter('node'))
    if not a.system_dialog and not any(n.get('package') == a.package for n in nodes):
        time.sleep(.3)
        continue
    if a.tap:
        for node in nodes:
            if node.get('text') == a.tap or node.get('content-desc') == a.tap:
                x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
                if x2>x1 and y2>y1:
                    call('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                    call('shell','rm',remote)
                    print('Tapped:',a.tap)
                    raise SystemExit(0)
    else:
        for node in nodes:
            text=node.get('text') or node.get('content-desc')
            if text: print(text[:1000],node.get('bounds'))
        call('shell','rm',remote)
        raise SystemExit(0)
    time.sleep(.3)
call('shell','rm',remote)
raise SystemExit('Expected application or control not visible; no action taken.')
