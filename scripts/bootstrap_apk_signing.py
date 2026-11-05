"""Provision Android signing identity entirely on a GitHub runner.

Only sealed GitHub secret payloads and a certificate fingerprint are exported.
The private keystore is temporary and is never committed or logged.
"""
import base64,hashlib,json,os,secrets,subprocess,tempfile
from pathlib import Path
from nacl.public import PublicKey,SealedBox

def b64(data):return base64.b64encode(data).decode('ascii')
def sealed(pub,data):return b64(SealedBox(PublicKey(base64.b64decode(pub,validate=True))).encrypt(data))
def certificate(path,password):
 env=dict(os.environ,AKG_KEYSTORE_PASSWORD=password)
 r=subprocess.run(['keytool','-exportcert','-alias','akg-retro-release','-keystore',str(path),'-storetype','PKCS12','-storepass:env','AKG_KEYSTORE_PASSWORD'],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)
 return hashlib.sha256(r.stdout).hexdigest()
mode=os.environ['MODE']
with tempfile.TemporaryDirectory(prefix='akg-signing-',dir=os.environ.get('RUNNER_TEMP')) as td:
 path=Path(td)/'signing.p12'
 if mode=='create':
  password=secrets.token_urlsafe(32);env=dict(os.environ,AKG_KEYSTORE_PASSWORD=password)
  cmd=['keytool','-genkeypair','-noprompt','-alias','akg-retro-release','-keyalg','RSA','-keysize','3072','-sigalg','SHA256withRSA','-validity','10000','-dname','CN=Ahmed Kamal Elgandy, O=AKG Retro Games','-storetype','PKCS12','-keystore',str(path),'-storepass:env','AKG_KEYSTORE_PASSWORD','-keypass:env','AKG_KEYSTORE_PASSWORD']
  subprocess.run(cmd,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
  fp=certificate(path,password);payload=json.dumps({'keystore_base64':b64(path.read_bytes()),'password':password,'alias':'akg-retro-release'},separators=(',',':')).encode();path.unlink()
  result={'certificate_sha256':fp,'repository':{'key_id':os.environ['REPO_KEY_ID'],'encrypted_value':sealed(os.environ['REPO_KEY'],payload)},'backup':{'key_id':os.environ['BACKUP_KEY_ID'],'encrypted_value':sealed(os.environ['BACKUP_KEY'],payload)}}
  Path('encrypted-apk-signing.json').write_text(json.dumps(result),encoding='utf8');print('Created sealed Android signing identity and backup; public certificate fingerprint:',fp)
 elif mode=='verify':
  config=json.loads(os.environ['SIGNING_JSON']);path.write_bytes(base64.b64decode(config['keystore_base64'],validate=True));fp=certificate(path,config['password']);path.unlink()
  if fp!=os.environ['EXPECTED_FINGERPRINT']:raise ValueError('APK signing certificate backup does not match')
  print('PASS Android signing identity matches its public certificate fingerprint')
 else:raise ValueError('Invalid mode')
