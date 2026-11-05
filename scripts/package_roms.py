"""Download user's indexed games into a private CI artifact, validating exact identity."""
import hashlib,json,time,urllib.error,urllib.parse,urllib.request
from pathlib import Path
out=Path('package-roms');out.mkdir(exist_ok=True)
rows=json.loads(Path('cheats/index.json').read_text(encoding='utf8'))['games']
for i,row in enumerate(rows,1):
    url='https://drive.google.com/uc?export=download&id='+urllib.parse.quote(row['source_id'])
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'AKGRetro-PrivatePreview/1'}),timeout=75) as response:data=response.read(10000001)
            break
        except urllib.error.HTTPError as e:
            if e.code not in (429,500,502,503,504) or attempt==3:raise
            time.sleep(2*(attempt+1))
    if len(data)>10000000 or hashlib.sha256(data).hexdigest()!=row['rom_sha256']:raise ValueError('ROM identity check failed: '+row['source_id'])
    ext='.nes' if row['system']=='NES' else '.smc' if row['system']=='SNES' else None
    if ext is None:raise ValueError('Unsupported system')
    (out/(row['source_id']+ext)).write_bytes(data)
    print(f'{i}/{len(rows)} verified',flush=True)
print('All supplied games verified; private artifact only')
