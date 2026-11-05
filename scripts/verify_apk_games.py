"""Audit every packaged game against the selected Drive entries and their hashes."""
import hashlib,json,sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
 index=json.loads(z.read('assets/drive-index.json'));catalog=json.loads(z.read('assets/cheats/index.json'))['games']
 excluded=set(index['excluded_ids']);drive={r['id'] for r in index['files'] if r['id'] not in excluded and r['name'].lower().endswith(('.nes','.smc','.sfc'))};rows={r['source_id']:r for r in catalog}
 if len(rows)!=len(catalog) or set(rows)!=drive:raise SystemExit('Drive and in-app catalog do not agree')
 expected={f"assets/roms/{r['source_id']}{'.nes' if r['system']=='NES' else '.smc'}" for r in catalog};packaged={n for n in z.namelist() if n.startswith('assets/roms/') and not n.endswith('/')}
 if expected!=packaged:raise SystemExit('APK ROM list differs from in-app catalog')
 for r in catalog:
  name=f"assets/roms/{r['source_id']}{'.nes' if r['system']=='NES' else '.smc'}"
  if hashlib.sha256(z.read(name)).hexdigest()!=r['rom_sha256']:raise SystemExit('APK game hash differs: '+r['source_id'])
 print(f'PASS {len(rows)} indexed ROM files packaged; each hash matches, excluded HTML absent; distinct content hashes {len({r["rom_sha256"] for r in catalog})}')
