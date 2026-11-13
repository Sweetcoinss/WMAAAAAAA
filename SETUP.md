# SWAT 4 for Android — إعداد جهاز آخر

## 0. المستودع
المشروع على: `https://github.com/Sweetcoinss/WMAAAAAAA` (خاص).
نسخة العمل السابقة: `https://github.com/Sweetcoinss/swat4-android`.

## 1. النسخ (clone)
```sh
git clone https://github.com/Sweetcoinss/WMAAAAAAA.git
cd WMAAAAAAA
```

## 2. المتطلبات على الجهاز الجديد

| الغرض | الأداة | ملاحظة |
|---|---|---|
| بناء الـ APK | JDK 21 (Temurin) | `java -version` يجب أن يظهر 21 |
| بناء الـ APK | Android SDK | `platforms;android-36`, `build-tools;36.0.0` |
| سكربت البناء | Python 3 | للـ `prepare-proot.py` و `ghidra2cpp.py` |
| سكربتات الـ runtime | Bash | على Windows: Git Bash |
| بناء الـ runtime (نوافذ) | Docker | في حاوية Linux arm64 (أو Linux arm64 حقيقي) |

### إعداد الـ SDK على Windows
```powershell
# مثال: C:\Android\android-sdk (نفس هذا الجهاز)
$env:ANDROID_HOME = "C:\Android\android-sdk"
$env:ANDROID_SDK_ROOT = "C:\Android\android-sdk"

# أو عبر التنزيل
cmdline-tools\bin\sdkmanager.bat --sdk_root="C:\Android\android-sdk" "platforms;android-36" "build-tools;36.0.0"
```
`C:\Android\android-sdk` هو الموجود على هذا الجهاز — انسخه إن أمكن (platforms 34…37 و build-tools موجودة) بدل إعاد التنزيل.

## 3. بناء وتشغيل
```sh
# 1. بناء الـ runtime (مرة واحدة) — Linux arm64 أو CI
docker run --rm --platform linux/arm64 -v "$PWD:/src" debian:trixie \
    sh /src/android/runtime/build-runtime.sh

# 2. إنشاء APK
cd android
.\gradlew.bat assembleFullDebug assembleFreeDebug     # Windows
./gradlew assembleFullDebug assembleFreeDebug         # Linux/macOS
```
الـ APK تتكوّن في:
- `app/build/outputs/apk/debug/app-full-debug.apk` (النسخة الرئيسية)
- `app/build/outputs/apk/debug/app-free-debug.apk` (النسخة المجانية)

## 4. على الهاتف
1. ثبّت `app-debug.apk`
2. انسخ مجلد اللعبة (`SWAT 4 Portable` — 1.74 GB) إلى كارت SD أو الذاكرة
3. افتح التطبيق → أول مرة يدلو الـ runtime (~120 MB) ← Wi-Fi
4. اختر مجلد اللعبة → التطبيق يتحقق ويحفظ المسار ويشغّل

## 5. الأسرار (GitHub Secrets) — اختياري
للتوقيع الحقيقي للـ APK في الـ CI (بدونها: توقيع بالـ debug key):
`OPENSWAT4_KEYSTORE_BASE64` · `OPENSWAT4_KEYSTORE_PASSWORD` · `OPENSWAT4_KEY_ALIAS` · `OPENSWAT4_KEY_PASSWORD`
أكثر من واحد فقط إن حابب تطرح الـ APK على Play Store.

## 6. الملفات اللي **مش** موجودة على المستودع
- بيانات اللعبة نفسها (`Swat4.exe` + الـ DLLs + المحتوى) — **لازم تنسخها يدوياً** للهاتف (الترخيص)
- `local.properties` و ملفات الـ SDK
- `runtime/out/` (الـ rootfs) — بينبني بالـ CI أو بواسطة Docker
- `jniLibs/libproot*.so` — بتتولد بواسطة `prepare-proot.py` في الـ CI

## 7. الـ APK لا يحتوي على اللعبة إطلاقاً
- الـ APK ~3 MB لكل نسخة (رئيسية ومجانية)
- الـ runtime ~120 MB — ينزّل أول مرة ويُحفظ على الجهاز
- اللعبة تُشغّل من مكانها (SD/ذاكرة) بدون نسخ

## 8. الفرق بين النسختين
| | `full` (رئيسية) | `free` (مجانية) |
|---|---|---|
| Identifiant | `fr.tibkpl.openswat4` | `fr.tibkpl.openswat4.free` |
| دقة العرض القصوى | 1080p | 720p |
| لعب جماعي عبر الشبكة المحلية | نعم | لا |
| التثبيت المتزامن | تنفعان معاً (نفسان بمعرّفين مختلفين) | |
