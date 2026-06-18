# Recall — Build Journal

A faithful, optimize-later record of how Recall was built **on a Mac with no Android Studio**, shipped to GitHub. Includes the dead-ends and rework on purpose — that's where the time went and where the speed-ups hide. Each stage links the commit that did it.

---

## Goal & hard constraints

- Android app: share an Instagram reel → transcribe audio → summarize → save locally → search. Also YouTube.
- **No Android Studio** (user didn't want the ~15 GB IDE).
- Free, public, distributed via **GitHub Releases** (Play Store ruled out: new-account policy gates production behind a 14-day, ~20-tester closed test).
- Bring-your-own Gemini key (no server, no secrets shipped).

## Toolchain (exact versions — reproducibility)

| Piece | Version / location |
|---|---|
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.9 (wrapper) |
| compileSdk / targetSdk | 35 · minSdk 24 |
| Compose BOM | 2024.10.01 |
| yt-dlp (on-device) | `io.github.junkfood02.youtubedl-android:library:0.18.1` + `:ffmpeg` (Maven Central) |
| Gemini model | `gemini-2.5-flash` (REST `generateContent`) |
| JDK (local) | Temurin/Homebrew **openjdk@17** → `/opt/homebrew/opt/openjdk@17` |
| Android SDK (local) | Homebrew cask **android-commandlinetools** → `/opt/homebrew/share/android-commandlinetools` (platform-tools, platforms;android-35, build-tools;35.0.0) |

## Two build setups (the "no Android Studio" trick)

- **Phase A — CI-only (no local toolchain at all).** Push → GitHub Actions (`.github/workflows/android.yml`) builds the APK with `actions/setup-java` + `android-actions/setup-android` + Gradle. Verify loop = push → wait ~2–5 min → download artifact. Got us a buildable app with *zero* local install.
- **Phase B — local builds (still no Android Studio).** Later installed just **openjdk@17 + android-commandlinetools via Homebrew** (~1.5 GB, vs 15 GB for Studio). `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew assembleDebug`. First build ~2m16s; **incremental builds 1–4 s**. CI stays as the release/cross-check pipeline.

---

## Stages

| Stage | Commit | What | Notes / timing |
|---|---|---|---|
| 1 | `c27a4fa` | Minimal Compose app + CI pipeline | First CI build **5m11s** (SDK download). Toolchain versions correct first try. |
| 2a | `e14c05f` | Embed yt-dlp (`youtubedl-android`), on-device resolver test screen | Isolated the riskiest dependency *before* building on it. APK 39 MB. |
| — | `9eafce8` | Add Gradle wrapper | So the repo opens in Android Studio / `./gradlew` works anywhere. CI switched to `./gradlew`. |
| 2b | `8079243` | Download audio → mp3 (ffmpeg) → Gemini transcribe+summarize | Full pipeline. APK 103 MB (ffmpeg+python). |
| 3 | `0b17f38` | Local SQLite library, search, topic filters, detail, **share-sheet** | Hand-written `SQLiteOpenHelper` (no Room/KSP — avoids annotation-processor risk in CI-blind builds). |
| 4 | `319cbc8` | PDF export (paginated, FileProvider) + **stable signing** | Committed keystore so builds update in place. |
| — | `fc8124c` | README + MIT license | |
| — | `51dffef` | Launcher icon from provided logo (`sips` → mipmaps) | |
| — | `60ac3b4` | **Background ingest service** — share & keep scrolling | Foreground service + notification; share returns you to Instagram immediately. |
| v1.0.1 | `ca62a52` | Auto-retry transient Gemini 503/429/500 with backoff | |
| v1.0.2 | `2742b61` | **Retry queue** — failed ingests saved + retryable from the library | DB migration to v2 (`failures` table). |

Public launch: repo flipped public, **v1.0.0 → v1.0.2** cut from local `assembleRelease`.

---

## Friction log (the expensive parts — optimize these)

1. **Expo was a dead-end for the core feature.** A whole earlier session built an Expo/React-Native app. yt-dlp is **Python** and cannot run in Expo/RN; the hand-rolled JS Instagram scraper (og:video / embed / api-v1) returned **200 but no video URL** — IG has fully walled logged-out video access. *Lesson: validate the single hardest capability (IG video bytes) before picking the framework. Going native Kotlin was forced by yt-dlp, and should have been the starting point.*
2. **Pipedream backend (prior session) burned ~6 exchanges** on `pipreqs` auto-packaging failures for `yt-dlp`/`google-generativeai`. *Lesson: don't use magic-dependency-detection hosts for heavy Python; on-device yt-dlp avoided the server entirely.*
3. **"Use yt-dlp" needed proof.** Installed real yt-dlp and ran `yt-dlp -g <reel>` from the Mac's residential IP → **it worked anonymously, no cookies**, where the JS scraper failed. This single test justified the whole native rewrite. *Lesson: a 30-second CLI test can replace hours of speculation.*
4. **Wrong dependency coordinates / JitPack.** `youtubedl-android` recent tags **fail to build on JitPack** (both forks). Correct source is **Maven Central** `io.github.junkfood02.youtubedl-android:0.18.1`. Verified via `repo1.maven.org/.../maven-metadata.xml` before adding. *Lesson: confirm coords against the actual repo metadata, not memory.*
5. **`getInfo().url` is null for DASH reels** ("needs merge"). Fix: don't fetch a URL — have yt-dlp **download best audio and transcode to mp3** (`-x --audio-format mp3`, needs bundled ffmpeg). Also dodges the video/audio merge.
6. **Gemini audio mime types.** IG audio is m4a (`audio/mp4`), which Gemini may reject; transcoding to **mp3** sidesteps it.
7. **Ephemeral CI signing → forced reinstalls.** Each GitHub Actions run signs with a *different* debug key, so test APKs wouldn't install over each other (signature mismatch) — every test build meant uninstall + data loss. Fixed with a **committed stable keystore** (`keytool` needed a JDK, which wasn't local yet → this is partly why we installed openjdk). *Lesson: set up stable signing before the first on-device test, not after several.*
8. **`gh repo edit --visibility public` flag rejected** by gh 2.94. Worked via API: `gh api -X PATCH repos/OWNER/recall -f visibility=public`.
9. **Pushing workflows needs the `workflow` OAuth scope** — first push rejected; fixed with `gh auth refresh -s workflow`.
10. **Gemini 503 "model overloaded"** surfaced raw to the user. Fixed with backoff retry; then a persistent **retry queue** so nothing is lost.
11. **Image as icon.** Provided logo was a full marketing banner; used `sips` to resize to mipmap densities. (The full banner is busy at icon size — an R-only crop would be sharper; left as-is per user.)

## Optimization candidates (next pass)

- **Start native, skip Expo** for any "needs a Python tool on-device" app.
- **Stable signing + local JDK on day one** (kills the reinstall/data-loss loop and CI-blind iteration).
- **Prove the hardest dependency with a CLI/throwaway test** before scaffolding.
- Consider committing a fixed debug keystore template + `keystore.properties` pattern as a reusable starter.
- Once this recipe is squeezed, graduate it into a Skill ("scaffold a native Kotlin + yt-dlp app, CI + local builds, no Android Studio").
