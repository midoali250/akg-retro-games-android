"""Build two test versions using the dedicated secret, never log or persist it."""
import base64,hashlib,json,os,re,shutil,subprocess,tempfile
from pathlib import Path
config=json.loads(os.environ['AKG_ANDROID_RELEASE_SIGNING_JSON'])
expected=json.loads(Path('updates/android-apk-signing-certificate.json').read_text())['certificate_sha256']
version=json.loads(Path('updates/version.json').read_text()).get('version_code')
if type(version) is not int or not 1<=version<2100000000:raise ValueError('Invalid Android version code')
if config['alias']!='akg-retro-release' or not re.fullmatch(r'[0-9a-f]{64}',expected):raise ValueError('Invalid signing configuration')
sdk=Path(os.environ['ANDROID_HOME']);apksigner=sdk/'build-tools'/'34.0.0'/'apksigner'
if not apksigner.is_file():raise RuntimeError('Android build tools not available')
with tempfile.TemporaryDirectory(prefix='akg-release-key-',dir=os.environ.get('RUNNER_TEMP')) as td:
 key=Path(td)/'release.p12';key.write_bytes(base64.b64decode(config['keystore_base64'],validate=True));env=dict(os.environ);env.pop('AKG_ANDROID_RELEASE_SIGNING_JSON',None);env.update({'AKG_RELEASE_KEYSTORE':str(key),'AKG_RELEASE_STORE_PASSWORD':config['password'],'AKG_RELEASE_KEY_ALIAS':config['alias'],'AKG_RELEASE_KEY_PASSWORD':config['password']})
 try:
  for target_version,dest in [(version,'ci-signed/AKG-Retro-Games-Android-signed.apk'),(version+1,'ci-signed-test/AKG-update-test-next.apk')]:
   env['AKG_VERSION_CODE']=str(target_version)
   subprocess.run(['./gradlew',':app:assembleRelease','--no-daemon'],env=env,check=True,timeout=600)
   apk=Path('app/build/outputs/apk/release/app-release.apk')
   output=subprocess.check_output([str(apksigner),'verify','--verbose','--print-certs',str(apk)],text=True,stderr=subprocess.STDOUT,timeout=60)
   match=re.search(r'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]+)',output)
   if not match or match.group(1).lower()!=expected:raise RuntimeError('Android release certificate mismatch')
   dest=Path(dest);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(apk,dest)
   digest=hashlib.sha256(dest.read_bytes()).hexdigest();(dest.parent/'SHA256SUMS.txt').write_text(digest+'  '+dest.name+'\n')
   print('PASS signed Android build',target_version,'certificate',expected,flush=True)
 finally:key.unlink(missing_ok=True)
