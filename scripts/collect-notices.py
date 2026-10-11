#!/usr/bin/env python3
from pathlib import Path
import argparse, shutil, tomllib
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('registry',type=Path);args=p.parse_args()
lock=tomllib.loads((root/'legal/engine-Cargo.lock').read_text())
lines=['sdocx2pdf 0.4.0 dependency notices','','Source: https://github.com/squ1dd13/sdocx2pdf/tree/v0.4.0','']
for pkg in lock['package']:
 if not pkg.get('source','').startswith('registry'):continue
 key=pkg['name']+'-'+pkg['version'];matches=list(args.registry.glob('*/'+key))
 if not matches:continue
 src=matches[0];manifest=tomllib.loads((src/'Cargo.toml').read_text())
 lines.append(key+' | '+manifest.get('package',{}).get('license','see package sources'))
 for path in src.iterdir():
  if path.is_file() and path.name.upper().startswith(('LICENSE','LICENCE','COPYING','NOTICE','AUTHORS','COPYRIGHT')):
   dest=root/'legal/dependencies'/key/path.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,dest)
(root/'legal/THIRD_PARTY.txt').write_text('\n'.join(lines)+'\n')
print(len(lines)-4,'dependency notices')
