import hashlib
import tempfile
import unittest
from pathlib import Path
from update_manifest import validate, verify_file

class ManifestTests(unittest.TestCase):
    def manifest(self):
        return {'schema_version':1, 'platform':'linux', 'version_code':2,
                'size_bytes':3, 'sha256':hashlib.sha256(b'abc').hexdigest(),
                'url':'https://updates.example.test/linux/package'}
    def test_accept_valid(self):
        self.assertEqual(validate(self.manifest(),'linux')['version_code'],2)
    def test_reject_other_platform(self):
        with self.assertRaises(ValueError): validate(self.manifest(),'android')
    def test_reject_http_and_credentials(self):
        for url in ['http://example.test/a','https://user:pass@example.test/a','https://example.test/a#x']:
            m=self.manifest();m['url']=url
            with self.assertRaises(ValueError): validate(m,'linux')
    def test_reject_bad_values(self):
        for key,value in [('size_bytes',True),('version_code',0),('sha256','xyz')]:
            m=self.manifest();m[key]=value
            with self.assertRaises(ValueError): validate(m,'linux')
    def test_file_integrity(self):
        m=self.manifest()
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'package';p.write_bytes(b'abc');self.assertTrue(verify_file(p,m))
            p.write_bytes(b'abd');self.assertFalse(verify_file(p,m))
            p.write_bytes(b'abcd');self.assertFalse(verify_file(p,m))
if __name__=='__main__':unittest.main()
