# LiveTranslate (边写边译)

[简体中文](README.md) | English

An Android tool that translates what you type in any app's input box as you type, and replaces your text with the translation in one tap. Chat, post or reply to emails in a foreign language without switching back and forth between a translator and the chat window.

![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm%20NC%201.0.0-orange)](LICENSE.md)

> The app's UI is currently Chinese only. UI labels below are quoted in Chinese with an English gloss.

## Features

- **Live input-box translation**: an accessibility service reads the paragraph under the cursor in the focused input box and translates it after a short pause (400 ms by default, adjustable). The translation appears right above the input box.
- **One-tap replace**: replaces only the translated paragraph and leaves the rest of your text untouched. Before replacing, it checks that neither the text nor the input box has changed; if it can't replace safely, it copies the translation instead.
- **Two trigger modes**: translate as you type, or translate only when you long-press the input box. Long-press is only observed, so the app's own paste / select-all menu still works.
- **Two display modes**: docked above the input box, or a draggable floating panel, with adjustable font size and opacity. The panel is an accessibility overlay, so it **needs no "draw over other apps" permission** and never steals keyboard focus.
- **Multiple providers**: Bilibili Index-Translate, Doubao (including its Volcano and Microsoft channels), Microsoft Translator, Google Translate, and any OpenAI-compatible API. Switch with one tap on the panel.
- **Translation styles** (LLM providers): casual, texting / internet slang, faithful, formal, or a custom style described in your own words.
- **Short-term context** (optional, LLM providers): if you start a new paragraph within 5 seconds of a successful replace, up to the last 3 source paragraphs (max 500 characters) are sent along as reference so omitted subjects resolve correctly. Kept in memory only.
- **Word mode**: splits the translation into words; tap a word to hear it and see a Chinese definition.
- **Privacy masking** (on by default): items on your local list (names, companies, addresses, …), plus emails, Chinese ID numbers and numbers of 6+ digits, are replaced by random codes before sending and restored on the device afterwards. A list item can have an alias, e.g. 张三 → "Zhang San".
- **Text-to-speech**: uses the system TTS, with selectable engine and speech rate.
- **App scope**: all apps, or only the apps you pick.
- **Staying alive in the background**: after a one-time grant via Shizuku or adb, the service is revived automatically if the system kills it. Also provides an ongoing notification, battery-optimization whitelisting, shortcuts to vendor autostart settings, and a Quick Settings tile to pause / resume.
- **Languages**: auto-detect, Simplified Chinese, Traditional Chinese, English, Japanese, Korean, French, German, Spanish, Russian.

## Privacy

- **Password fields are never read.**
- **Privacy masking** (Settings → 隐私脱敏): anything matched by your list or the built-in rules is replaced by a random code (e.g. `ZQX7`) before sending. Codes are drawn fresh for every request, the provider only ever sees the codes, and the original values are restored on the device.
  - Short-term context, custom styles and word definitions are masked too.
  - In realtime mode, a half-typed list item or number right before the cursor is masked as well.
  - If codes come back missing or damaged, Replace is disabled.
  - A network-level check blocks any request that still contains a list item.
  - Limits: only literal text is masked, so relationships and other meaning still go out, and masked parts may translate less accurately.
  - Speech and copy use the restored translation; online TTS engines and IME cloud clipboards are not covered.
- Text is sent only to the provider you selected. Word definitions use your custom AI provider when it is the current provider, and Bilibili otherwise.
- No analytics, ads or crash-reporting SDKs.
- The custom-AI API key and the privacy list are encrypted with an Android Keystore key (AES-GCM) and stored on the device only; system backup is disabled (`allowBackup=false`).
- Short-term context and the word-definition cache live in memory only and are gone when the process ends.
- Cleartext HTTP is allowed so you can reach model servers on localhost or your LAN; use HTTPS for anything on the internet.
- The code contains a developer-only node diagnostic broadcast. Senders must hold `android.permission.DUMP`, which only the adb shell has, so regular apps cannot trigger it.

## Requirements

- Android 11 (API 30) or later
- Optional: [Shizuku](https://github.com/RikkaApps/Shizuku), for one-tap accessibility setup and background hardening

## Getting started

1. Download the APK from [Releases](../../releases), or [build it yourself](#building-from-source).
2. Open the app, tap 「去开启」 (Enable) on the home screen, and turn on 「边写边译 · 输入框实时翻译」 in the system accessibility settings.
   - If Shizuku is installed and running, the button becomes 「用 Shizuku 一键开启」 (Enable with Shizuku) and does everything for you.
   - If the accessibility toggle is greyed out: Android 13+ restricts sideloaded apps. Open App info → top-right menu → "Allow restricted settings", then try again.
3. Type in any app's input box and the translation panel appears above it:
   - 「替换」 (Replace) swaps the paragraph for the translation; 「复制」 (Copy), 「朗读」 (Speak) and 「单词」 (Words) are there when you need them;
   - tap the provider name at the top-left of the panel to switch providers, and the language chip to swap direction.
4. Go through 「设置 → 防止被杀后台」 (Settings → Keep alive) from top to bottom so the system doesn't kill the service.

Without Shizuku, you can run one adb command from a computer to enable automatic recovery:

```bash
adb shell pm grant com.livetranslate android.permission.WRITE_SECURE_SETTINGS
```

## Providers

| Provider | Requires | Notes |
| --- | --- | --- |
| Bilibili Index-Translate (default) | Nothing | Streaming; models `Index-Translate-35B-A3B` / `index-mt-9b` / `index-mt-2b`; supports styles and short-term context |
| Doubao / Doubao·Volcano / Doubao·Microsoft | A Doubao account | Log in through the in-app web page, or paste the doubao.com cookie from a desktop browser |
| Microsoft Translator | Nothing | |
| Google Translate | Nothing | Google must be reachable |
| Google Translate 2 | An API key at build time | Google must be reachable; unavailable if the build has no key, see [Building from source](#building-from-source) |
| Custom AI | Base URL, model, API key | OpenAI-compatible Chat Completions or Responses API with streaming; enter the base URL up to `/v1` and the path is appended automatically; the key may be empty for local models; supports styles and short-term context |

> Apart from Custom AI, the built-in providers call unofficial endpoints used by each service's web app or browser extension. They may change or stop working at any time. Use them reasonably and follow each service's terms of use.

## Building from source

Requirements:

- JDK 17
- Android SDK (compileSdk 35)
- Gradle 8.13 (downloaded automatically by the Gradle Wrapper)

Create or edit `local.properties` in the project root. It is listed in `.gitignore`; **never commit it**:

```properties
sdk.dir=/path/to/Android/Sdk

# Optional: API key for "Google Translate 2" (the key built into Chrome's page-translation component).
# Leave it empty to disable that provider; other providers are unaffected.
GOOGLE_TRANSLATE_API_KEY=
```

An environment variable with the same name, `GOOGLE_TRANSLATE_API_KEY`, works too, which is handy for CI.

Build and test:

```bash
./gradlew assembleDebug      # debug build; use gradlew.bat on Windows
./gradlew test               # unit tests
./gradlew assembleRelease    # minified, resource-shrunk release build
```

APKs are written to `app/build/outputs/apk/`.

> Release builds are currently signed with the debug key so they can be sideloaded directly. For long-term distribution, switch to your own signing config and keep the keystore and passwords outside the repository.

## Project layout

```text
app/src/main/java/com/livetranslate/
├── a11y/        accessibility service: input watching, translation ranges, safe replace, boot recovery
├── data/        settings storage, API-key encryption, translation styles
├── keepalive/   ongoing notification, watchdog job, Quick Settings tile, vendor autostart pages
├── net/         OkHttp wrapper, SSE parsing, Doubao cookie sync, privacy guard interceptor
├── overlay/     translation panel (Jetpack Compose) and overlay window
├── privacy/     privacy masking: matching rules, random codes, streaming restore and checks
├── shizuku/     runs grant commands as the shell user through Shizuku
├── translate/   providers, translation pipeline (debounce / cancel / cache), short-term context, word definitions
├── tts/         text-to-speech
└── ui/          home screen, settings, app picker, Doubao login
app/src/test/    unit tests
.agents/notes/   design decision records (Chinese)
```

## License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE.md). **Commercial use is not permitted.**

- Allowed: using, modifying and redistributing it for noncommercial purposes, such as personal study, research and hobby projects, and use by charities, educational institutions and other noncommercial organizations;
- Not allowed: any commercial use, for example selling the software or a modified version, or building it into a commercial product or paid service;
- When redistributing, include the license text (or its URL) and the `Required Notice:` copyright line.

This is only a summary; the full text in [LICENSE.md](LICENSE.md) governs. For commercial licensing, please contact the author first.

Third-party libraries used by this project (AndroidX, Kotlin Coroutines, OkHttp, Shizuku API, etc.) and the Gradle Wrapper files remain under their own licenses.

## Disclaimer

- This project is not affiliated with Bilibili, Doubao (ByteDance), Microsoft, Google, DeepSeek or any other service provider. All names and trademarks belong to their respective owners.
- Translations are produced by third-party services and may be inaccurate. Check anything important before you send it.
- The text you type is sent to the third-party provider you select. Do not use the app for private or confidential content.
- The software is provided "as is", without warranty of any kind.

## Acknowledgements

- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [OkHttp](https://github.com/square/okhttp)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
