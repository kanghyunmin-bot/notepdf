#!/usr/bin/env python3
"""Real-device/emulator instrumentation. Fixtures stay in ignored build directories."""
from pathlib import Path
import argparse,subprocess,os,shutil,zipfile
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--embedded',type=Path,required=True);p.add_argument('--handwritten',type=Path,required=True);p.add_argument('--serial',default='emulator-5582');a=p.parse_args()
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path.home()/'Library/Android/sdk')));java=Path(os.environ.get('JAVA_HOME','/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home'));tool=sdk/'build-tools/35.0.0';jar=sdk/'platforms/android-35/android.jar';b=root/'build/android-tests'
for d in ['assets','classes','dex']: (b/d).mkdir(parents=True,exist_ok=True)
shutil.copy2(a.embedded,b/'assets/embedded.sdocx');shutil.copy2(a.handwritten,b/'assets/handwritten.sdocx');(b/'assets/corrupt.sdocx').write_text('not a Samsung document')
(b/'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="local.notepdf.tests"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><application android:label="NotePDF tests"/><instrumentation android:name="local.notepdf.SmokeTests" android:targetPackage="local.notepdf"/></manifest>')
subprocess.run([str(tool/'aapt2'),'link','-I',str(jar),'--manifest',str(b/'AndroidManifest.xml'),'-A',str(b/'assets'),'-o',str(b/'unsigned.apk')],check=True)
subprocess.run([str(java/'bin/javac'),'-source','8','-target','8','-encoding','UTF-8','-cp',os.pathsep.join(map(str,[jar,root/'build/android/classes'])),'-d',str(b/'classes'),str(root/'android/tests/SmokeTests.java')],check=True)
subprocess.run([str(java/'bin/jar'),'cf',str(b/'classes.jar'),'-C',str(b/'classes'),'.'],check=True)
env=dict(os.environ,JAVA_HOME=str(java))
subprocess.run([str(tool/'d8'),'--min-api','26','--lib',str(jar),'--classpath',str(root/'build/android/classes.jar'),'--output',str(b/'dex'),str(b/'classes.jar')],check=True,env=env)
with zipfile.ZipFile(b/'unsigned.apk','a') as z:
 for f in (b/'dex').glob('*.dex'):z.write(f,f.name)
keys=root/'build/private-keys';subprocess.run([str(tool/'apksigner'),'sign','--ks',str(keys/'notepdf-release.jks'),'--ks-key-alias','notepdf','--ks-pass','file:'+str(keys/'notepdf-release.password'),'--out',str(b/'test.apk'),str(b/'unsigned.apk')],check=True,env=env)
adb=[str(sdk/'platform-tools/adb'),'-s',a.serial]
subprocess.run(adb+['install','-r',str(root/'dist/NotePDF-1.0.0-rc.1-android-arm64.apk')],check=True)
subprocess.run(adb+['install','-r',str(b/'test.apk')],check=True)
r=subprocess.run(adb+['shell','am','instrument','-w','-r','local.notepdf.tests/local.notepdf.SmokeTests'],text=True,capture_output=True,timeout=420);print(r.stdout);print(r.stderr)
(b/'results.txt').write_text(r.stdout+r.stderr)
if 'FAIL:' in r.stdout or 'INSTRUMENTATION_CODE: -1' not in r.stdout:raise SystemExit('Instrumentation failed')
subprocess.run(adb+['shell','am','force-stop','local.notepdf'],check=True)
r=subprocess.run(adb+['shell','am','instrument','-w','-r','-e','restore','true','local.notepdf.tests/local.notepdf.SmokeTests'],text=True,capture_output=True,timeout=60);print(r.stdout)
(b/'restore.txt').write_text(r.stdout+r.stderr)
if 'FAIL:' in r.stdout or 'INSTRUMENTATION_CODE: -1' not in r.stdout:raise SystemExit('Restore failed')
subprocess.run(adb+['pull','/sdcard/Android/data/local.notepdf/files/handwriting-export.pdf',str(b/'handwriting-export.pdf')],check=True)
