# Run from repository root with unicodedata2==15.1.0 installed.
# Pass the Unicode 15.1.0 CaseFolding.txt file as the first argument.
import unicodedata2 as u, json, hashlib, sys
assert u.unidata_version=='15.1.0'
source=open(sys.argv[1], encoding='utf-8').read()
assert hashlib.sha256(source.encode()).hexdigest() == '4e55acfdc32825a22e87670e9056a3bf94ad7c5400065778e9e10f8314372bcf'
fold={}
for line in source.splitlines():
 line=line.split('#')[0].strip()
 if not line: continue
 code,status,value,*_=map(str.strip,line.split(';'))
 if status in ('C','F'):
  fold[int(code,16)]=''.join(chr(int(n,16)) for n in value.split())
rows=[]
for cp in range(0x110000):
 if 0xd800<=cp<=0xdfff:continue
 c=chr(cp);d=u.normalize('NFD',c); f=u.normalize('NFD',fold.get(cp,c));ccc=u.combining(c)
 if d!=c or f!=c or ccc: rows.append([cp,d if d!=c else None,f if f!=c else None,ccc])
# Canonical compositions, including algorithmic Hangul. Only non-excluded
# pairs that really compose under Unicode 15.1 NFC are included.
compositions=[]
for cp in range(0x110000):
 if 0xd800<=cp<=0xdfff: continue
 c=chr(cp); decomp=u.decomposition(c)
 if not decomp or decomp.startswith('<'): continue
 parts=[int(x,16) for x in decomp.split()]
 if len(parts)==2 and u.normalize('NFC',''.join(map(chr,parts)))==c:
  compositions.append([parts[0],parts[1],c])
for l in range(0x1100,0x1113):
 for v in range(0x1161,0x1176):
  lv=0xac00+((l-0x1100)*21+(v-0x1161))*28
  compositions.append([l,v,chr(lv)])
  for t in range(1,28): compositions.append([lv,0x11a7+t,chr(lv+t)])
obj={'compositions':compositions,'unicode':'15.1.0' ,'caseFoldingSha256':hashlib.sha256(source.encode()).hexdigest(),'description':'Canonical decomposition; full default case fold then decomposition; canonical combining class. Unicode 15.1.0.', 'rows':rows}
open('experiments/tag-name/ucd-15.1.json','w',encoding='utf8').write(json.dumps(obj,ensure_ascii=False,separators=(',',':'))+'\n')
print(len(rows),len(open('experiments/tag-name/ucd-15.1.json','rb').read()),obj['caseFoldingSha256'])

# Independent expected keys for the actual shared inputs. Evaluate the exact
# documented NFC -> full fold -> NFC policy, then NFD only for comparison
# with the SQL prototype's NFD keys.
white = set(chr(cp) for cp in [9,10,11,12,13,32,133,160,5760,8192,8193,8194,8195,8196,8197,8198,8199,8200,8201,8202,8232,8233,8239,8287,12288])
fixture=json.load(open('tests/fixtures/tag-name-policy-vectors.json',encoding='utf8'))
inputs=[]
for p in fixture['pairs']: inputs.extend((p['left'],p['right']))
for case in fixture['scopedCases']: inputs.extend(t['name'] for t in case['tags'])
inputs.extend(('', ' \u00a0\t', 'Café'))
def expected(s):
 s=' '.join(part for part in ''.join(' ' if c in white else c for c in s).split(' ') if part)
 s=u.normalize('NFC',s)
 s=''.join(fold.get(ord(c),c) for c in s)
 return u.normalize('NFD',u.normalize('NFC',s))
open('experiments/tag-name/expected-keys.json','w',encoding='utf8').write(json.dumps([expected(s) for s in inputs],ensure_ascii=False,indent=2)+'\n')
