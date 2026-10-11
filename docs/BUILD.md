# 빌드와 서명

현재 빌드 스크립트는 macOS 호스트를 기준으로 합니다. Git으로 받는 엔진 소스는 `engine/`에 포함되어 있어 원격 `master`의 변경을 따라가지 않습니다. Rust 1.99.0, Xcode Command Line Tools, Python 3.9 이상을 사용했습니다. 라이선스 수집 스크립트만 Python 3.11 이상이 필요합니다.

## Mac

```sh
cargo build --release --locked --manifest-path engine/Cargo.toml
python3 scripts/build.py
```

Apple Silicon에서 실행합니다. 다른 호스트에서 교차 빌드한다면 `aarch64-apple-darwin` 타깃으로 엔진을 빌드하고 `--engine`으로 지정하세요. 완성 앱에 시스템 Python이나 Rust가 필요하지 않습니다.

## Android ARM64

Android SDK platform 35, build-tools 35.0.0, NDK r29, JDK 11을 설치한 후 경로를 지정합니다.

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="/absolute/path/to/android-ndk-r29"
export JAVA_HOME="/absolute/path/to/jdk11"
python3 scripts/build-engine-android.py
python3 scripts/build-android.py
```

엔진은 Android API 26 대상으로 링크하며 16KB ELF 정렬을 요청합니다. APK는 네이티브 엔진을 `nativeLibraryDir`에 실행 가능하게 설치합니다. 쓰기 가능한 앱 데이터 폴더에서 실행 파일을 실행하지 않습니다. `android:extractNativeLibs=true`가 필요합니다.

서명 키는 첫 빌드 때 `build/private-keys/`에 생성되고 Git에서 제외됩니다. **이 키를 안전하게 보관해야 설치된 앱을 계속 업데이트할 수 있습니다.** 새 키로 재서명한 APK는 기존 앱에 업데이트 설치되지 않습니다. 기존 키를 지정하려면 `NOTEPDF_KEYSTORE`, `NOTEPDF_PASSWORD_FILE`을 설정합니다. 비밀번호 파일의 내용은 도구 인수나 로그로 출력하지 않습니다. 별칭은 `notepdf`입니다.

## 테스트

```sh
xcrun swiftc -swift-version 5 -framework AppKit -framework PDFKit Sources/Conversion.swift tests/main.swift -o /tmp/notepdf-core-tests
/tmp/notepdf-core-tests
python3 scripts/test-android.py --serial emulator-5582 --embedded /path/to/21-page.sdocx --handwritten /path/to/3-page.sdocx
```

Android 테스트는 테스트 APK를 따로 만듭니다. 사용자의 노트는 `build/` 안의 테스트 자산에만 들어가며 저장소·배포 APK·소스 ZIP에는 포함하지 않습니다. 테스트가 기대하는 페이지 수는 21/3입니다. 다른 fixture를 사용할 때는 테스트의 기대값을 조정하세요. 연결된 실제 기기에서 실행하려면 `--serial`을 그 기기의 ID로 지정합니다.

## 릴리스

- APK 서명 검증과 `zipalign -c -P 16 4` 검사를 통과해야 합니다.
- Mac 앱 서명 검증은 Apple 공증과 별개입니다.
- 테스트 APK, 노트, 결과 PDF, 인증서 개인 키, 비밀번호, 빌드 캐시는 배포하지 않습니다.
- 릴리스에 각 파일 SHA-256과 실제 검증한 OS·기기 범위를 명시합니다.
