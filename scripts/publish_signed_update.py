"""Sign a single public release asset for this repository's platform.

A workflow invokes this on v<integer> release publication or manually. This
helper refuses draft/prerelease, wrong-platform, ambiguous and overwrite cases.
Private seeds stay in GitHub Actions secrets, not in repository files.
"""
import json,os,re,subprocess,sys,tempfile,urllib.parse
from pathlib import Path
repo=os.environ['GITHUB_REPOSITORY'];prefix='midoali250/akg-retro-games-'
if not repo.startswith(prefix):raise SystemExit('Unexpected repository')
platform=repo[len(prefix):];extensions={'android':'.apk','windows':'.exe','linux':'.appimage','macos':'.zip'}
if platform not in extensions:raise SystemExit('Unsupported platform')
if os.environ.get('TEST_ONLY','').lower()=='true':
 import base64
 with tempfile.TemporaryDirectory(prefix='akg-signing-test-') as tmp:
  name='akg-update-test'+extensions[platform];asset=Path(tmp)/name;asset.write_bytes(b'AKG signing integration test; not a release')
  url='https://github.com/'+repo+'/releases/download/v3/'+name;out=Path(tmp)/'manifest.json'
  subprocess.run([sys.executable,'engine-source/updates/publisher/python/sign_manifest.py','--asset',str(asset),'--platform',platform,'--version-code','3','--url',url,'--public-key','updates/public-key.json','--output',str(out)],check=True,timeout=120)
  sys.path.insert(0,'engine-source/updates/client/python');from signed_updates import verify_manifest
  cfg=json.loads(Path('updates/public-key.json').read_text());key=base64.b64decode(cfg['public_key_base64'],validate=True)
  value=verify_manifest(out.read_bytes(),key,'akg-retro-games',platform,2,{('github.com',443)})
  if value['version_code']!=3 or value['size_bytes']!=asset.stat().st_size:raise RuntimeError('Signed test failed')
 print('PASS real platform secret signed a test package; pinned public key verified it. No release was published.')
 sys.exit(0)
tag=os.environ.get('RELEASE_TAG','');version=os.environ.get('VERSION_CODE','');requested=os.environ.get('ASSET_NAME','');event=os.environ.get('EVENT_NAME','')
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+\-]{0,99}',tag):raise SystemExit('Invalid release tag')
if not version:
 match=re.fullmatch(r'v([1-9][0-9]{0,8})',tag)
 if not match:
  if event=='release':print('No numeric v<version-code> tag; not publishing an update manifest.');sys.exit(0)
  raise SystemExit('Version code required')
 version=match.group(1)
if not re.fullmatch(r'[1-9][0-9]{0,8}',version):raise SystemExit('Invalid version code')
def gh(*args):return subprocess.check_output(['gh',*args],text=True,timeout=180)
release=json.loads(gh('release','view',tag,'--repo',repo,'--json','isDraft,isPrerelease,assets'))
if release['isDraft'] or release['isPrerelease']:
 if event=='release':print('Draft or prerelease: no update manifest published.');sys.exit(0)
 raise SystemExit('Only published non-prerelease versions can be used for updates')
assets=[x['name'] for x in release['assets']]
if 'akg-update-manifest.json' in assets:raise SystemExit('Refusing to overwrite an existing signed manifest')
candidates=[x for x in assets if x.lower().endswith(extensions[platform])]
if requested:
 if requested not in candidates:raise SystemExit('Requested platform asset was not found')
 name=requested
elif len(candidates)==1:name=candidates[0]
else:raise SystemExit('Specify a unique platform release asset')
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+ -]{0,179}',name):raise SystemExit('Unsafe release asset name')
url='https://github.com/'+repo+'/releases/download/'+urllib.parse.quote(tag,safe='')+'/'+urllib.parse.quote(name,safe='')
with tempfile.TemporaryDirectory(prefix='akg-release-') as tmp:
 subprocess.run(['gh','release','download',tag,'--repo',repo,'--pattern',name,'--dir',tmp],check=True,timeout=300)
 asset=Path(tmp)/name
 subprocess.run([sys.executable,'engine-source/updates/publisher/python/sign_manifest.py','--asset',str(asset),'--platform',platform,'--version-code',version,'--url',url,'--public-key','updates/public-key.json','--output',str(Path(tmp)/'akg-update-manifest.json')],check=True,timeout=180)
 subprocess.run(['gh','release','upload',tag,str(Path(tmp)/'akg-update-manifest.json'),'--repo',repo],check=True,timeout=180)
print('Published signed update manifest for',platform,'version',version)
