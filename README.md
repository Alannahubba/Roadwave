# Roadwave for Android

Listen to internet radio and play music stored on your phone. Roadwave supports favorites, six presets, background playback, a sleep timer, and Android Auto.

Choose **Settings → Language** to use English, Korean, Japanese, or Simplified Chinese. On Android 13 or later, the same language preference is available in the system settings for Roadwave.

## Build

With JDK 17 or later, Android SDK 36, and Gradle 9.5.0 installed, run `gradle -p android :app:assembleRelease`. On Windows, you can also run `powershell -File android/build.ps1`, which stages the project in an ASCII path. The bundled signing configuration is for local testing.

## Song identification

Roadwave displays track details published by a station when available. The optional **Identify** and **Auto on** controls send a fingerprint of the stream to Shazam. Auto identification starts off, and the app does not use the microphone.

## License

Roadwave for Android is licensed under [GPL-3.0-only](LICENSE). Third-party components and fonts retain their respective [licenses](licenses/).

## 한국어

국내외 인터넷 라디오와 휴대폰에 저장된 음악 파일을 재생하는 Android 앱입니다. 즐겨찾기, 프리셋, 백그라운드 재생, 취침 타이머, Android Auto를 지원합니다.

- [사용법, Android Auto 테스트, 빌드 안내](android/README.md)
- [기본 방송국 목록](catalog.json)
- [외부 폰트 및 구성 요소 라이선스](licenses/)
