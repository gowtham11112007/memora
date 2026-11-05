# Memora

**Your life, remembered privately.** An Android second brain that turns your notifications into an expense manager, reminders and searchable notes, on your phone.

## What it does
- **Tracks money automatically:** reads payment and bank notifications from apps you allow, then shows balance, totals, categories and a weekly chart.
- **Remembers things:** sorts bills, deliveries, tickets and events into Second Brain, with search and pins.
- **Asks before reminding:** when a message mentions a date, a popup asks whether to set a reminder.
- **Answers questions:** about your own records on the phone, and general ones through Gemini if you add a free key.
- **Summarises by email:** optional weekly totals through Resend.

## Privacy
- Only apps you turn on are read, and every app starts off.
- OTPs, passwords, CVVs and PINs are dropped before anything is saved, and long reference numbers are masked.
- Notifications and records never leave the phone. Only general questions you type go to Gemini.
- App lock is optional, and uses your phone's screen lock. Not built yet: database encryption. See [docs/privacy.md](docs/privacy.md).

## Set up
You need Android Studio's SDK and JDK 17.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The APK is at `app/build/outputs/apk/debug/Memora.apk`.

On the phone:
1. Install the APK and allow notifications.
2. Go to Settings → Notification reading and turn on notification access. If Android blocks it, go to App info → ⋮ → Allow restricted settings.
3. Turn on your payment apps and your SMS app, and set battery use to Unrestricted.
4. Optional: add your free Gemini and Resend keys in Settings → AI and email. Keys never go in the code.

For presenting, turn on Settings → Demo mode. See [docs/demo-script.md](docs/demo-script.md).

## Built with
Kotlin, Jetpack Compose, Room, NotificationListenerService, WorkManager, JUnit and Robolectric. Docs start at [docs/README.md](docs/README.md).
