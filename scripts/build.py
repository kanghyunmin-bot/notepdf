#!/usr/bin/env python3
"""Build an offline, self-contained Apple Silicon app. Never replaces installed apps."""
from pathlib import Path
import argparse, hashlib, os, plistlib, shutil, subprocess
root=Path(__file__).resolve().parents[1]
repo=root
parser=argparse.ArgumentParser()
parser.add_argument('--engine', type=Path, default=root/'engine/target/release/sdocx2pdf')
parser.add_argument('--output',type=Path,default=repo/'build/NotePDF/NotePDF.app')
args=parser.parse_args()
engine=args.engine.resolve()
if not engine.is_file(): raise SystemExit('Supply --engine pointing to a macOS arm64 sdocx2pdf 0.4.0 executable.')
architecture=subprocess.check_output(['file',str(engine)],text=True)
if 'arm64' not in architecture: raise SystemExit('This build requires the Apple Silicon (arm64) engine.')
app=args.output.resolve()
contents=app/'Contents'; mac=contents/'MacOS'; res=contents/'Resources'
for d in [mac,res]: d.mkdir(parents=True,exist_ok=True)
subprocess.run(['xcrun','swiftc','-swift-version','5','-O','-target','arm64-apple-macosx14.0','-framework','AppKit','-framework','PDFKit',str(root/'Sources/Conversion.swift'),str(root/'Sources/main.swift'),'-o',str(mac/'NotePDF')],check=True)
shutil.copy2(engine,res/'sdocx2pdf')
shutil.copy2(root/'Assets/Logo.png',res/'Logo.png')
shutil.copytree(root/'legal',res/'Licenses',dirs_exist_ok=True)
shutil.copy2(root/'README.md',res/'README.md')
iconset=app.parent/'NotePDF.iconset';iconset.mkdir(exist_ok=True)
for size in [16,32,128,256,512]:
 for scale in [1,2]:
  filename=f'icon_{size}x{size}'+('@2x' if scale==2 else '')+'.png'
  subprocess.run(['sips','-z',str(size*scale),str(size*scale),str(root/'Assets/Logo.png'),'--out',str(iconset/filename)],check=True,stdout=subprocess.DEVNULL)
subprocess.run(['iconutil','-c','icns',str(iconset),'-o',str(res/'AppIcon.icns')],check=True)
info={
 'CFBundleExecutable':'NotePDF','CFBundleIdentifier':'local.notepdf.converter',
 'CFBundleName':'NotePDF','CFBundleDisplayName':'NotePDF','CFBundlePackageType':'APPL',
 'CFBundleShortVersionString':'1.0.0','CFBundleVersion':'1','CFBundleIconFile':'AppIcon',
 'LSMinimumSystemVersion':'14.0','NSHighResolutionCapable':True,'NSPrincipalClass':'NSApplication',
 'CFBundleDocumentTypes':[{'CFBundleTypeName':'Samsung Notes document','CFBundleTypeRole':'Viewer','LSHandlerRank':'Alternate','LSItemContentTypes':['local.notepdf.sdocx']}],
 'UTImportedTypeDeclarations':[{'UTTypeIdentifier':'local.notepdf.sdocx','UTTypeDescription':'Samsung Notes document','UTTypeConformsTo':['public.data'],'UTTypeTagSpecification':{'public.filename-extension':['sdocx']}}]
}
with (contents/'Info.plist').open('wb') as f:plistlib.dump(info,f)
for path in [res/'sdocx2pdf',app]:
 subprocess.run(['codesign','--force','--sign','-',str(path)],check=True)
subprocess.run(['codesign','--verify','--deep','--strict',str(app)],check=True)
print(app)
print('Engine SHA256:',hashlib.sha256(engine.read_bytes()).hexdigest())
