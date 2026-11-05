"""Generate one update signing seed in runner memory and encrypt for two GitHub secrets."""
import base64,json,os,pathlib
from nacl.signing import SigningKey
from nacl.public import PublicKey,SealedBox

def b64(b):return base64.b64encode(b).decode('ascii')
def encrypt(pub,plaintext):return b64(SealedBox(PublicKey(base64.b64decode(pub,validate=True))).encrypt(plaintext))
mode=os.environ['BOOTSTRAP_MODE']
if mode=='create':
 seed=SigningKey.generate(); secret=b64(bytes(seed)).encode('ascii')
 result={'public_key':b64(bytes(seed.verify_key)), 'repository':{'key_id':os.environ['REPO_KEY_ID'],'encrypted_value':encrypt(os.environ['REPO_KEY'],secret)}, 'backup':{'key_id':os.environ['BACKUP_KEY_ID'],'encrypted_value':encrypt(os.environ['BACKUP_KEY'],secret)}}
 pathlib.Path('encrypted-update-key.json').write_text(json.dumps(result),encoding='utf-8')
 print('Generated Ed25519 public key and sealed secret payloads; no private key written to disk.')
elif mode=='verify':
 seed=os.environ.get('SIGNING_SEED','');expected=os.environ['EXPECTED_PUBLIC_KEY']
 key=SigningKey(base64.b64decode(seed,validate=True)); assert b64(bytes(key.verify_key))==expected, 'stored key does not match public key'
 msg=b'AKG update signing key verification';key.verify_key.verify(msg,key.sign(msg).signature)
 print('PASS stored signing key matches the published public key')
else:raise ValueError('invalid mode')
