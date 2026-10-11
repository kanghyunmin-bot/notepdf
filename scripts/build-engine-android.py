#!/usr/bin/env python3
from pathlib import Path
import os,subprocess
root=Path(__file__).resolve().parents[1]
ndk=Path(os.environ['ANDROID_NDK_HOME']);tool=next((ndk/'toolchains/llvm/prebuilt').iterdir())/'bin'
env=os.environ.copy();env['CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER']=str(tool/'aarch64-linux-android26-clang');env['RUSTFLAGS']='-C link-arg=-Wl,-z,max-page-size=16384'
subprocess.run(['rustup','target','add','aarch64-linux-android'],check=True)
subprocess.run(['cargo','build','--locked','--release','--target','aarch64-linux-android','--manifest-path',str(root/'engine/Cargo.toml')],env=env,check=True)
