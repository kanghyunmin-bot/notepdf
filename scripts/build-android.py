#!/usr/bin/env python3
"""Build signed ARM64 APK with SDK tools; no Gradle or downloaded runtime."""
from pathlib import Path
import argparse,os,shutil,subprocess,secrets,zipfile,hashlib
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--engine',type=Path,default=ROOT/'engine/target/aarch64-linux-android/release/sdocx2pdf');args=p.parse_args()
SDK=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Library/Android/sdk')))
JAVA=Path(os.environ.get('JAVA_HOME','/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home'))
TOOLS=SDK/'build-tools/35.0.0';ANDROID=SDK/'platforms/android-35/android.jar'
B=ROOT/'build/android';B.mkdir(parents=True,exist_ok=True)
for name in ['classes','res','gen','dex','assets','lib']:
 d=B/name
 if d.exists():shutil.rmtree(d)
 d.mkdir()
shutil.copytree(ROOT/'android/res',B/'res',dirs_exist_ok=True)
(B/'res/drawable').mkdir()
subprocess.run(['sips','-z','192','192',str(ROOT/'Assets/Logo.png'),'--out',str(B/'res/drawable/app_icon.png')],check=True,stdout=subprocess.DEVNULL)
lib=B/'lib/arm64-v8a';lib.mkdir(parents=True)
shutil.copy2(args.engine,lib/'libnotepdf_engine.so')
licenses=B/'assets/Licenses';licenses.mkdir()
shutil.copy2(ROOT/'LICENSE',licenses/'NotePDF-MIT.txt')
shutil.copy2(ROOT/'legal/sdocx2pdf-LICENSE.txt',licenses/'sdocx2pdf-MIT.txt')
shutil.copy2(ROOT/'legal/THIRD_PARTY.txt',licenses/'Dependencies.txt')
with (licenses/'Dependency-licenses.txt').open('w') as out:
 for f in sorted((ROOT/'legal/dependencies').rglob('*')):
  if f.is_file():out.write('\n\n===== '+str(f.relative_to(ROOT/'legal/dependencies'))+' =====\n'+f.read_text(errors='replace'))
subprocess.run([str(TOOLS/'aapt2'),'compile','--dir',str(B/'res'),'-o',str(B/'resources.zip')],check=True)
subprocess.run([str(TOOLS/'aapt2'),'link','-I',str(ANDROID),'--manifest',str(ROOT/'android/AndroidManifest.xml'),'--java',str(B/'gen'),'-A',str(B/'assets'),str(B/'resources.zip'),'-o',str(B/'unsigned.apk')],check=True)
sources=[str(f) for d in [ROOT/'android/src',B/'gen'] for f in d.rglob('*.java')]
subprocess.run([str(JAVA/'bin/javac'),'-source','8','-target','8','-encoding','UTF-8','-classpath',str(ANDROID),'-d',str(B/'classes'),*sources],check=True)
subprocess.run([str(JAVA/'bin/jar'),'cf',str(B/'classes.jar'),'-C',str(B/'classes'),'.'],check=True)
env=os.environ.copy();env['JAVA_HOME']=str(JAVA)
subprocess.run([str(TOOLS/'d8'),'--min-api','26','--lib',str(ANDROID),'--output',str(B/'dex'),str(B/'classes.jar')],env=env,check=True)
with zipfile.ZipFile(B/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
 for f in (B/'lib').rglob('*.so'):z.write(f,str(f.relative_to(B)))
 for f in (B/'dex').glob('*.dex'):z.write(f,f.name)
subprocess.run([str(TOOLS/'zipalign'),'-f','-P','16','4',str(B/'unsigned.apk'),str(B/'aligned.apk')],check=True)
keys=ROOT/'build/private-keys';keys.mkdir(mode=0o700,exist_ok=True)
key=Path(os.environ.get('NOTEPDF_KEYSTORE',str(keys/'notepdf-release.jks')))
pw=Path(os.environ.get('NOTEPDF_PASSWORD_FILE',str(keys/'notepdf-release.password')))
if not key.exists():
 if os.environ.get('NOTEPDF_KEYSTORE'):raise SystemExit('Requested signing key does not exist')
 pw.write_text(secrets.token_urlsafe(32)+'\n');pw.chmod(0o600)
 subprocess.run([str(JAVA/'bin/keytool'),'-genkeypair','-alias','notepdf','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=NotePDF, O=kanghyunmin-bot, C=KR','-keystore',str(key),'-storepass:file',str(pw),'-keypass:file',str(pw)],check=True);key.chmod(0o600)
out=ROOT/'dist/NotePDF-1.0.0-rc.1-android-arm64.apk';out.parent.mkdir(exist_ok=True)
subprocess.run([str(TOOLS/'apksigner'),'sign','--ks',str(key),'--ks-key-alias','notepdf','--ks-pass','file:'+str(pw),'--out',str(out),str(B/'aligned.apk')],env=env,check=True)
subprocess.run([str(TOOLS/'apksigner'),'verify','--verbose',str(out)],env=env,check=True)
subprocess.run([str(TOOLS/'zipalign'),'-c','-P','16','4',str(out)],check=True)
print(out);print(hashlib.sha256(out.read_bytes()).hexdigest())
