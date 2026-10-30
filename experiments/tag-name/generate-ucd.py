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
obj={'unicode':'15.1.0','caseFoldingSha256':hashlib.sha256(source.encode()).hexdigest(),'description':'Canonical decomposition; full default case fold then decomposition; canonical combining class. Unicode 15.1.0.', 'rows':rows}
open('experiments/tag-name/ucd-15.1.json','w',encoding='utf8').write(json.dumps(obj,ensure_ascii=False,separators=(',',':'))+'\n')
print(len(rows),len(open('experiments/tag-name/ucd-15.1.json','rb').read()),obj['caseFoldingSha256'])
