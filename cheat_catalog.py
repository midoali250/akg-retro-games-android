"""Match cheat entries to exact ROM bytes; do not infer codes from titles."""
import hashlib
import json
import re
from pathlib import Path

def verified_for_file(catalog, rom_file):
    digest=hashlib.sha256(Path(rom_file).read_bytes()).hexdigest()
    data=json.loads(Path(catalog).read_text(encoding='utf-8'))
    if data.get('schema_version')!=1 or not isinstance(data.get('games'),list):raise ValueError('invalid cheat catalog')
    results=[]
    for game in data['games']:
        if game.get('rom_sha256')!=digest:continue
        for cheat in game.get('verified_cheats',[]):
            if cheat.get('verified') is not True or cheat.get('rom_sha256')!=digest:raise ValueError('wrong-game or unverified code')
            if cheat not in results:results.append(cheat)
    return results
