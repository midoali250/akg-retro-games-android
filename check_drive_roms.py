"""Audit user-supplied public Drive ROM files on GitHub Actions; never stores ROMs."""
import concurrent.futures
import hashlib
import json
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

INDEX=Path(__file__).with_name('drive-index.json')

def inspect(entry):
    url='https://drive.google.com/uc?export=download&id='+urllib.parse.quote(entry['id'])
    request=urllib.request.Request(url,headers={'User-Agent':'RetroCollection-Investigator/1'})
    try:
        with urllib.request.urlopen(request,timeout=60) as r:
            data=r.read(10_000_001)
        if len(data)>10_000_000:
            return {**entry,'status':'too_large'}
        row={**entry,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
        if data[:128].lstrip().lower().startswith((b'<!doctype html',b'<html')):
            return {**row,'status':'html_not_rom'}
        if entry['name'].lower().endswith('.nes'):
            if len(data)<16 or data[:4]!=b'NES\x1a':return {**row,'status':'invalid_nes_header'}
            row['console']='NES'
            flags6,flags7=data[6],data[7]
            row['mapper']=(flags6>>4)|(flags7&0xf0)
            row['nes2']=(flags7&0x0c)==0x08
            if not row['nes2']:
                expected=16+data[4]*16384+data[5]*8192+(512 if flags6&4 else 0)
                row['expected_minimum_bytes']=expected
                row['status']='header_valid' if len(data)>=expected else 'truncated_rom'
            else:row['status']='header_valid_nes2_unchecked_length'
        elif entry['name'].lower().endswith(('.smc','.sfc')):
            row['console']='SNES';row['status']='snes_candidate' if len(data)>=32768 else 'too_small'
        else:row['status']='unrecognized_extension'
        return row
    except Exception as e:
        return {**entry,'status':'download_error','error':str(e)[:180]}

def main():
    source=json.loads(INDEX.read_text(encoding='utf8'))
    items=[x for x in source['files'] if x['id'] not in source['excluded_ids']]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        rows=list(pool.map(inspect,items))
    dup=defaultdict(list)
    for row in rows:
        if row.get('sha256'):dup[row['sha256']].append(row['name'])
    report={'total_indexed':len(source['files']),'excluded_user_approved':len(source['excluded_ids']),
            'downloaded':sum('sha256' in x for x in rows),'files':rows,
            'identical_files':[x for x in dup.values() if len(x)>1]}
    Path('drive-rom-audit.json').write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
    counts=defaultdict(int)
    for r in rows:counts[r['status']]+=1
    print('Results:',dict(counts),'duplicate groups:',len(report['identical_files']))
    errors=[x for x in rows if x['status'] not in ('header_valid','header_valid_nes2_unchecked_length','snes_candidate')]
    for x in errors:print('REVIEW:',x['name'],x['status'],x.get('error',''))
    if errors:sys.exit(1)
if __name__=='__main__':main()
