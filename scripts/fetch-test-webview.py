#!/usr/bin/env python3
"""Prepare an official Chromium CI WebView for an explicitly approved AVD test.
Does not install an APK or change the system provider. This is a CI test build,
not a replacement for Google Play's signed stable WebView on a production phone.
"""
from pathlib import Path
import hashlib,json,struct,urllib.request,zlib

revision='1731338'
url=f'https://storage.googleapis.com/chromium-browser-snapshots/Android_Arm64/{revision}/chrome-android.zip'
out=Path(__file__).resolve().parent.parent/'.build/downloads'
out.mkdir(parents=True,exist_ok=True)
def get_range(start,end):
    requested=f'bytes={start}' if end=='' else f'bytes={start}-{end}'
    request=urllib.request.Request(url,headers={'Range':requested})
    response=urllib.request.urlopen(request,timeout=40)
    if response.status!=206:
        response.close();raise RuntimeError('Publisher did not honor HTTP Range; archive was not downloaded')
    return response
with get_range('-131072','') as response:
    directory=response.read(131073)
if len(directory)>131072:raise RuntimeError('Unexpected ZIP directory size')
pos=0;entry=None
while True:
    pos=directory.find(b'PK\x01\x02',pos)
    if pos<0:break
    fields=struct.unpack_from('<4s6H3I5H2I',directory,pos)
    name=directory[pos+46:pos+46+fields[10]].decode()
    if name=='chrome-android/apks/SystemWebView.apk':entry=fields;break
    pos+=46+fields[10]+fields[11]+fields[12]
if entry is None:raise RuntimeError('No WebView APK in publisher archive')
crc_expected,compressed,uncompressed,offset=entry[7],entry[8],entry[9],entry[-1]
if entry[4]!=8 or uncompressed>512*1024*1024:raise RuntimeError('Unsupported ZIP entry')
with get_range(offset,offset+1023) as response:header=response.read(1024)
local=struct.unpack_from('<4s5H3I2H',header)
start=offset+30+local[-2]+local[-1]
part=out/f'chromium-{revision}-SystemWebView.apk.part'
final=out/f'chromium-{revision}-SystemWebView.apk'
if final.exists():raise SystemExit(f'Already prepared: {final}')
decoder=zlib.decompressobj(-15);crc=0;size=0;remaining=compressed
try:
    with get_range(start,start+compressed-1) as response,part.open('wb') as dest:
        while remaining:
            chunk=response.read(min(1024*1024,remaining))
            if not chunk:raise RuntimeError('Truncated publisher response')
            remaining-=len(chunk);data=decoder.decompress(chunk)
            size+=len(data)
            if size>uncompressed:raise RuntimeError('Unexpected uncompressed size')
            crc=zlib.crc32(data,crc);dest.write(data)
    if not decoder.eof or size!=uncompressed or crc!=crc_expected:raise RuntimeError('ZIP entry verification failed')
    part.rename(final)
except BaseException:
    part.unlink(missing_ok=True);raise
sha=hashlib.sha256(final.read_bytes()).hexdigest()
(final.with_suffix('.source.json')).write_text(json.dumps({'source':url,'revision':revision,'purpose':'AVD-only CI test build; not production stable','zipCrc32':f'{crc:08x}','bytes':size,'sha256':sha},indent=2)+'\n')
print(final);print('SHA-256',sha)
