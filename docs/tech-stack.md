# Memora: Tech Stack and How It Works

Memora is a native Android app (package `com.pulse.app`). It reads allowed notifications on the phone, filters out sensitive ones, extracts expenses, reminders and to-dos, stores them locally, and answers questions from the stored records.

Target device: Nothing Phone (3a), Android 16. minSdk 26, targetSdk 35.

For the full data flow see [architecture.md](architecture.md). This file is the stack and tooling reference.

## Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.1.21 |
| UI | Jetpack Compose with Material 3 (Compose BOM 2025.05.01), Material icons core |
| State | ViewModel + StateFlow, Room `Flow` observed by screens (lifecycle 2.9.0) |
| Local database | Room 2.7.1 (SQLite), code generated with KSP, encrypted with SQLCipher 4.19.1 |
| Async | Kotlin coroutines |
| Dependency wiring | By hand: `PulseApp` holds a `PulseGraph` with the database and repository. No DI library. |
| Notification capture | Android `NotificationListenerService` (`PulseNotificationListener`) |
| Reminders | Exact alarm (fires on time) plus a WorkManager 2.10.1 job (survives a restart); whichever runs first delivers and cancels the other |
| AI (optional) | Groq (about 0.5 s) then Gemini for chat, with a 25 s limit; Gemini then Groq for Smart reading; Resend for email |
| Build | Gradle, Android Gradle Plugin 8.9.2, version catalog (`gradle/libs.versions.toml`), Java 17 |
| Tests | JUnit 4.13.2, Robolectric 4.14.1, Roborazzi 1.43.0 (renders screens to images), Compose UI test |

## Project structure

Source root: `app/src/main/java/com/pulse/app`

- `ui/`: Compose screens (home, calendar, ask, settings, records, theme, components). Each screen has a `*Content` composable that takes plain data, so tests can render it.
- `service/`: `PulseNotificationListener`. Thin: it extracts text and forwards it.
- `domain/`: pure Kotlin, unit tested without Android (privacy filter, capture gate, extractors, categoriser).
- `data/`: Room entities, DAOs, `CaptureRepository` (the single door for real and simulated notifications).
- `PulseApp.kt`: application class that builds the database and repository.

## How it works

1. **Capture.** Once the user grants Notification access, Android sends every app's notifications to `PulseNotificationListener`. It skips Memora's own notifications and group summaries, then hands the sender, title and text to `CaptureRepository`.
2. **Gate.** `CaptureGate` decides what happens to each message:
   - Apps the user has not allowed are ignored, and their content is never read (default: no apps).
   - Empty messages are dropped.
   - Sensitive messages (OTP, PIN, CVV, password, verification code) are dropped before anything is stored.
   - Muted senders are skipped. Duplicate payments (same bank reference, or same app, amount and name within a short window) are not recorded twice.
3. **Extract.** Rules and regex read the message in memory and pull out money, dates, reminders and to-dos. SMS and chat apps need a bank alert before a message counts as money. Everyday chat matches no rule and is dropped. Smart reading (optional AI) can add to the rules but never removes what they found, and money is decided by rules alone.
4. **Mask and store.** Card, reference and phone numbers are masked, keeping the last 4 characters. Records, tasks and masked raw events are saved in one transaction in the on-device Room database. Raw events older than 24 hours are deleted.
5. **Confirm.** A detected date becomes an unconfirmed task and a popup asks the user. Yes schedules it, To-do moves it, No deletes it. The exception is the opt-in "Automatic reminders" setting, for clear dates from non-chat apps, with Undo.
6. **Remind.** Confirmed reminders fire as a notification with Done, In 10 min and Tomorrow buttons. Repeating ones move to their next date.
7. **Ask.** Questions about the user's records are answered on the phone from fixed question types, with source records shown. No network and no SQL built from user text. Commands like "remember", "remind me" and "find" and offline answers (maths, dates, units, help) also run locally. Anything else goes to the AI chat, which sees only what the user typed.

## Privacy rules

- Records never leave the device.
- Notification text leaves only through `CaptureRepository.readSmartly` while Smart reading is on: masked, and never codes, muted people or money messages. The privacy text must say this goes to Google and Groq.
- INTERNET is used only for AI chat, Smart reading and Resend email. Chat sends what the user types, plus category totals only if the user opts in.
- The notifications-read log holds the sender and outcome only, never the message, for 7 days.
- Built-in API keys live only in the gitignored `secrets.properties`, never in code or Git.
- An LLM never writes or runs raw SQL.

More in [privacy.md](privacy.md).

## Commands

Run from the `pulse` folder. Java 17 is required:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

| Task | Command |
|---|---|
| Unit tests | `./gradlew testDebugUnitTest` |
| Render screens to `app/build/shots` | `./gradlew testDebugUnitTest -Pshots` |
| Build debug APK (`app/build/outputs/apk/debug/Memora.apk`) | `./gradlew assembleDebug` |
| Install on phone (USB debugging on) | `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/Memora.apk` |

Do not run `adb shell dumpsys notification` on the Nothing 3a: it drops the debugging connection.

## Design

Ink panels, vivid violet and lime highlights on soft tinted tiles, folder-tab cards, a notched bottom bar, arrow chips and gentle motion. Details are in [design.md](design.md).
