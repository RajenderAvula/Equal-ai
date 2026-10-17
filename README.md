# Equal AI

Android voice assistant (Kotlin + Compose). Everything runs on the phone; the only outside service is the Gemini API with the key you paste in Setup.

```
app/                       -> the Android module (package com.equalai.assistant)
build.gradle.kts, settings.gradle.kts, gradle.properties -> Gradle project files (repo root)
.github/workflows/build.yaml -> CI: builds a debug APK on every push to main
```

## What it does

| Feature | How |
|---|---|
| Answer calls, talk, record, summarize | After N seconds of ringing (default 15, set in Setup) Equal answers, switches to speaker, greets the caller, and holds a short conversation. Gemini listens to each turn and replies. The whole call is recorded by the app's own recorder. When the line drops, a transcript and summary are generated and read aloud. |
| Schedule calls / WhatsApp / SMS | Scheduler tab, or say "Hey Equal, WhatsApp Rahul tomorrow 5 pm saying ...". Saved on the phone, survives reboot. |
| Read notifications and ask what next | Reads each alert aloud, beeps, listens for "reply ...", "open it", "dismiss". |
| Background voice | Foreground microphone service. Wake word "Hey Equal" (switchable) or the mic button. |
| Offline mode | Switch on the main screen. On-device speech recognition and built-in commands only. Calls are answered with a "leave a message" greeting and recorded; the transcript and summary are produced once you are back online. |
| Open apps / do anything / summarize all | "open Chrome", "go back", "tap Send", "type hello", "read screen", "summarize screen", "summarize everything". |
| Google API | Paste your AI Studio key in Setup. Stored encrypted; not in the APK. |

## Install

1. Put this folder's contents at the root of your repo (replace the old files; delete the old `MainActivity.kt` and `CloudClient.kt` if you still have them). The GitHub Action builds the APK; download it from the run's Artifacts. Or open the folder in Android Studio.
2. Build and install the APK. Allow all permissions when asked (microphone, phone, call log, contacts, SMS, notifications).
3. Open **Setup**:
   - Android 13+ sideload: App info -> ⋮ -> **Allow restricted settings**, then enable Accessibility and Notification listener.
   - Allow exact alarms, display over other apps, battery "Unrestricted".
   - Paste your Google API key. Use a model that accepts audio input (the default `gemini-2.5-flash` does).
   - Enter your name for the greeting.
4. **Calls** tab -> switch on **Auto-answer calls**.
5. Open the app once after every reboot (Android does not allow starting the microphone service from boot). Calls are only answered while the voice service is running.

## How a call works

1. Phone rings. If you don't pick up within the delay, Equal answers and switches to speaker.
2. The greeting plays: it says the call is being recorded and transcribed.
3. Equal listens through the microphone until the caller pauses (up to 15 s), sends that clip to Gemini, speaks Gemini's reply, and repeats (up to 8 turns or 5 minutes), then says goodbye and hangs up.
4. The recorder runs for the whole call and saves a WAV in the app's private storage (kept 30 days, playable in the Calls tab).
5. When the line drops, the transcript built during the call is summarized by Gemini. If Gemini could not be used during the call (offline, no key, error), the whole recording is sent to Gemini in 4-minute parts for transcription first. If there is no internet, the call waits as "Waiting for internet" and is finished automatically (or tap **Summarize now**).

## The big limitation: microphone during calls

Android does not give apps the caller's voice. Equal works around it: it talks through the loudspeaker so the caller hears it through the phone's own microphone, and it listens to the caller through the loudspeaker. This needs the phone to let apps use the microphone during a call.

- The recorder tries four audio sources in turn (`voice_call`, `voice_recognition`, `voice_communication`, `mic`). `voice_call` captures both sides cleanly but only works on some custom ROMs.
- Many phones silence the microphone for ordinary apps while a call is active. Equal detects that (the mic cannot even hear its own greeting), tries the other sources, and if all fail the call card shows "This phone blocked the microphone during the call". Nothing can be summarized then.
- Even where it works, quality is "speakerphone quality": quiet room, phone on a table. The caller may hear a little echo.

Test before relying on it: call your phone from another phone, let it ring, and listen. The Calls tab and the log (Alerts tab, bottom) show what happened.

## Speech-to-text

The in-built recorder and Gemini do the speech-to-text (per turn during the call, whole recording afterwards). There is no on-device transcription of call audio: Android's offline recognizer cannot read audio files reliably, so offline calls are transcribed once you are online.

## Things to know

- **Recording consent.** The greeting tells every caller the call is recorded and transcribed. Keep it; many places legally require it.
- **Privacy.** Call audio is sent to the Gemini API. On Google's free tier, inputs may be used to improve Google's products; use a paid-tier key if that matters to you. Recordings stay on the phone only.
- **Caller number** needs the call-log permission; without it calls show as "Unknown".
- **Screen reading.** The accessibility service sees every app's windows. Text is read only when you ask (read/summarize screen). Password fields are skipped. "Summarize screen" sends that text to Gemini.
- **Safety.** Calls, texts, WhatsApp messages and replies are read back and need a spoken "yes".
- **Scheduled "Call & speak"** now dials from your own SIM, switches to speaker and speaks the text; the same microphone/speaker limits apply (the person hears it through the phone's speaker pickup).
- **Not for Google Play.** SMS, call handling and accessibility automation are restricted policies. Sideload or distribute privately.
- This code was written without an Android compiler; expect to fix a few small build errors on first sync.
