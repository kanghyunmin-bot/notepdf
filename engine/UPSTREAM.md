# Engine provenance

- Project: https://github.com/squ1dd13/sdocx2pdf
- Tag: v0.4.0
- Commit: `372ef723a6857b2b878ee9a1dfb9520a8f83edf8`
- License: MIT, preserved as `LICENSE`.

The source is vendored to keep the shipped converter reviewable and rebuildable. On Android, scirs2-core 0.5.0 compiles `integration/mobile_ffi.rs` which references `libc`, but its upstream default feature set does not enable the optional libc dependency. `sdocx2pdf/Cargo.toml` adds an Android-only direct scirs2-core dependency with `features = ["libc"]`, using Cargo feature unification. Cargo.lock records that dependency edge. This does not change the handwriting algorithms.

The Android linker uses `-Wl,-z,max-page-size=16384` for 16KB page-size compatibility. The program is packaged under the filename `libnotepdf_engine.so` as an extracted, executable PIE in the application's native library directory. Its source and command-line interface remain in this directory. Mac uses the upstream conversion code without that target-specific feature.
