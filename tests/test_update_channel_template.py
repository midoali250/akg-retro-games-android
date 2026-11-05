import json,unittest
from pathlib import Path
class UpdateChannelTemplateTests(unittest.TestCase):
 def test_unconfigured_platform_isolated(self):
  d=json.loads(Path('updates/channel.template.json').read_text(encoding='utf8'))
  self.assertEqual(d['product'],'akg-retro-games');self.assertEqual(d['platform'],"android")
  self.assertEqual(d['package_format'],"apk");self.assertIsNone(d['feed_url']);self.assertIsNone(d['publisher_public_key']);self.assertEqual(d['status'],'not-configured')
if __name__=='__main__':unittest.main()
