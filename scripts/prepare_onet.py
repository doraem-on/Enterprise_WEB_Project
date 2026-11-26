"""Extract an attributed, reproducible career-exploration subset from O*NET 30.0."""
import csv, io, json, sys, zipfile
from pathlib import Path
z = zipfile.ZipFile(sys.argv[1])
def rows(name):
    path = next(n for n in z.namelist() if n.endswith('/' + name))
    return list(csv.DictReader(io.StringIO(z.read(path).decode('utf-8-sig')), delimiter='\t'))
occupations = rows('Occupation Data.txt')
skills = rows('Skills.txt')
codes = {'47-2031.00','51-7011.00','51-7021.00','37-3011.00','45-2092.00','51-6052.00','51-6051.00','35-2014.00','35-2021.00','43-3031.00','43-9061.00','43-4121.00','27-1012.00','49-9071.00','53-7064.00','51-6011.00'}
result=[]
for o in occupations:
    code=o['O*NET-SOC Code']
    if code not in codes: continue
    ranked=sorted((s for s in skills if s['O*NET-SOC Code']==code and s['Scale ID']=='IM'),key=lambda s:-float(s['Data Value']))[:6]
    result.append({'id':code,'title':o['Title'],'description':o['Description'],'skills':', '.join(s['Element Name'] for s in ranked),'source':'O*NET 30.0','url':'https://www.onetonline.org/link/summary/'+code})
Path('src/main/resources/data/careers.json').write_text(json.dumps(result,indent=2))
print(f'Exported {len(result)} occupations with their six highest-importance skills.')
