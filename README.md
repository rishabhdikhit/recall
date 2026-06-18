# Recall

**Turn the videos you watch into a searchable memory.** Share an Instagram reel (or a YouTube link) into Recall, and it transcribes the audio, summarizes the core information, auto-tags it by topic, and saves it locally — so weeks later you can *search* for what someone actually said and find it instantly.

No account. No server. Your data stays on your phone.

---

## What it does

- **Share → save.** Share a reel from Instagram straight into Recall (or paste any link).
- **On-device extraction.** A bundled [yt-dlp](https://github.com/yt-dlp/yt-dlp) pulls the audio right on your phone — no backend, and your phone's normal connection is what makes Instagram work.
- **Transcribe + summarize.** [Google Gemini](https://aistudio.google.com) turns the audio into a clean transcript, a catchy title, a tight summary, and a topic tag — in one call.
- **A real library.** Browse a card feed, **search** across every title, summary and transcript, and **filter by topic**.
- **Export.** Save any single entry — or your whole filtered library — as a **PDF** (handy when you switch phones).

## Privacy

- Your Gemini API key is stored **only on your device**.
- The only thing that leaves your phone is the reel's **audio**, sent to Google for transcription — exactly what any AI summarizer must do. Nothing else is uploaded; there is no Recall server.

## Setup (2 minutes)

1. **Install the app.** Download the latest `recall.apk` from [Releases](../../releases), open it, and allow "install from unknown sources."
2. **Get a free Gemini key.** Go to [aistudio.google.com/apikey](https://aistudio.google.com/apikey), create a key (free tier is plenty for personal use).
3. **Paste it** into Recall → **Settings** → Save.

That's it. Open a reel in Instagram → **Share → Recall** → **Analyze & Save**.

## Honest limitations

- **Public reels only.** Private or age-restricted reels can't be fetched.
- **Instagram occasionally breaks extraction.** It works the large majority of the time from a phone; if one reel fails, try another. There's no 100%-reliable free method — paid services use residential-proxy farms.
- **You bring your own Gemini key**, so any cost is yours (it's effectively free at personal volume).

## Build from source

It's a standard Gradle Android project.

```bash
git clone https://github.com/rishabhdikhit/recall.git
```

Open the folder in **Android Studio** and hit Run, or from the command line:

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and the Android SDK (Android Studio bundles both).

## Tech

Native **Kotlin + Jetpack Compose** · **youtubedl-android** (embedded yt-dlp + ffmpeg) · **Gemini 2.5 Flash** · local **SQLite** · on-device **PDF** export. No backend.

## License

[MIT](LICENSE)
