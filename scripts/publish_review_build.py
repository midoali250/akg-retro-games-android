"""Publish a previously successful GitHub Actions build as a review prerelease."""
import hashlib,json,os,re,subprocess,tempfile
from pathlib import Path
repo=os.environ['GITHUB_REPOSITORY'];prefix='midoali250/akg-retro-games-'
if not repo.startswith(prefix):raise SystemExit('Unexpected repository')
platform=repo[len(prefix):]
artifacts={'android':('android-signed-review-apk','.apk'),'windows':('AKG-Retro-Games-Windows-playable-preview','.exe'),'linux':('AKG-Retro-Games-Linux-playable-preview','.appimage'),'macos':('AKG-Retro-Games-macOS-playable-preview','.zip')}
if platform not in artifacts:raise SystemExit('Unexpected platform')
run=os.environ['BUILD_RUN_ID'];tag=os.environ['RELEASE_TAG']
if not re.fullmatch(r'[1-9][0-9]{5,20}',run) or not re.fullmatch(r'akg-review-[A-Za-z0-9][A-Za-z0-9._-]{0,80}',tag):raise SystemExit('Invalid run ID or release tag')
def gh(*args):return subprocess.check_output(['gh',*args],text=True,timeout=300)
run_info=json.loads(gh('api','repos/'+repo+'/actions/runs/'+run))
if run_info['status']!='completed' or run_info['conclusion']!='success' or run_info['repository']['full_name']!=repo:raise SystemExit('Build run is not successful for this repository')
head=run_info['head_sha']
existing=subprocess.run(['gh','release','view',tag,'--repo',repo],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
if existing.returncode==0:raise SystemExit('Release tag already exists; will not overwrite it')
name,ext=artifacts[platform]
with tempfile.TemporaryDirectory(prefix='akg-review-') as t:
 gh('run','download',run,'--repo',repo,'--name',name,'--dir',t)
 files=[p for p in Path(t).rglob('*') if p.is_file() and p.name.lower().endswith(ext)]
 if len(files)!=1:raise SystemExit('Expected one platform file, found '+str(len(files)))
 asset=files[0];digest=hashlib.sha256(asset.read_bytes()).hexdigest();checks=[p.read_text(encoding='utf-8-sig',errors='replace') for p in Path(t).rglob('*') if p.is_file() and 'sha256' in p.name.lower()]
 if not checks or not any(digest.lower() in c.lower() for c in checks):raise SystemExit('Build artifact checksum did not match')
 checksum=Path(t)/'AKG-SHA256.txt';checksum.write_text(digest+'  '+asset.name+'\n')
 warnings={'android':'Signed Android review APK. The previous debug-signed preview cannot be upgraded in-place; uninstalling it may remove its local saves. Android 8 or newer; arm64 or x86_64.', 'windows':'Windows x64 portable executable. The application is unsigned.', 'linux':'Linux x86_64 AppImage. The application is unsigned.', 'macos':'macOS Apple Silicon application ZIP. It is unsigned and not notarized.'}[platform]
 notes=('Playable review build, not a final release. Not all cheats and stage selection are validated. '+warnings+' The update channel has not been tested end-to-end on a real published update; desktop installs are manual. Source commit '+head+'; verified GitHub Actions run '+str(run)+'. Release assets do not expire automatically.')
 gh('release','create',tag,'--repo',repo,'--target',head,'--title','AKG Retro Games — '+platform+' review','--notes',notes,'--draft','--prerelease')
 gh('release','upload',tag,str(asset),str(checksum),'--repo',repo)
 gh('release','edit',tag,'--repo',repo,'--draft=false')
 release=json.loads(gh('release','view',tag,'--repo',repo,'--json','url,assets'))
 matches=[x for x in release['assets'] if x['name']==asset.name]
 if len(matches)!=1:raise SystemExit('Published asset missing')
 print('PLATFORM',platform,'RELEASE',release['url'],'ASSET',matches[0]['url'],'SHA256',digest,flush=True)
