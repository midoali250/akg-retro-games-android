import hashlib,json,tempfile,unittest
from pathlib import Path
from cheat_catalog import verified_for_file
class CheatCatalogTests(unittest.TestCase):
 def test_no_cheat_without_exact_rom_and_verification(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);rom=p/'game.nes';rom.write_bytes(b'test-game');sha=hashlib.sha256(b'test-game').hexdigest();other=hashlib.sha256(b'other').hexdigest()
   code={'rom_sha256':sha,'code':'EXAMPLE','verified':True}
   db=p/'catalog.json';db.write_text(json.dumps({'schema_version':1,'games':[{'rom_sha256':sha,'verified_cheats':[code]}]}))
   self.assertEqual(verified_for_file(db,rom),[code]);rom.write_bytes(b'other');self.assertEqual(verified_for_file(db,rom),[])
   rom.write_bytes(b'test-game');code['verified']=False;db.write_text(json.dumps({'schema_version':1,'games':[{'rom_sha256':sha,'verified_cheats':[code]}]}))
   with self.assertRaises(ValueError):verified_for_file(db,rom)
 def test_collection_index_has_no_fabricated_cheats(self):
  data=json.loads(Path('cheats/index.json').read_text());self.assertEqual(len(data['games']),97)
  self.assertTrue(all(len(x['rom_sha256'])==64 and not x['verified_cheats'] and not x['verified_stage_select'] for x in data['games']))
if __name__=='__main__':unittest.main()
