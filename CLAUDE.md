# Memora
Android app (Kotlin, Compose, Room). Reads allowed notifications, filters sensitive ones,
extracts expenses/deadlines, stores locally, answers questions from stored records.
Target device: Nothing Phone (3a), Android 16. minSdk 26, targetSdk 35, package com.pulse.app.

## Rules
- Local-first. Notification text and records never leave the device.
- INTERNET is only for optional Gemini chat and Resend email. Chat sends what the user types, plus category totals only if they opt in.
- Record questions, chat commands and offline answers run on the phone. Keys are typed in the app, never in code or Git.
- Detected reminders stay unconfirmed until the user says yes on the popup.
- Allowed-apps only (default none). Drop OTP/password/CVV/PIN messages before storing.
- Rules/regex first, LLM later and optional. Never let an LLM write or run raw SQL.
- Keep code minimal. Every regex change needs a unit test with a real sample message.
- Keep only money and reminder messages. SMS apps get the stricter check in CaptureRepository.
- Don't run `adb shell dumpsys notification` on the Nothing 3a: it drops the debugging connection.

## Structure (app/src/main/java/com/pulse/app)
- ui/ (Compose screens: home, calendar, ask, settings, records, theme, components)  - service/ (NotificationListenerService)
- domain/ (filter, capture gate, extractor, categoriser)  - data/ (Room entities, DAOs, CaptureRepository)
- PulseApp.kt builds the database + repository by hand (no DI library).

## Commands (run from this folder)
- Java 17 is required: export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
- Unit tests: ./gradlew testDebugUnitTest
- Render screens to app/build/shots: ./gradlew testDebugUnitTest -Pshots
- Build debug APK: ./gradlew assembleDebug  -> app/build/outputs/apk/debug/app-debug.apk
- Install on phone (USB debugging on): ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

## Design
- Ink panels, vivid violet and lime highlights on soft tinted tiles. Folder-tab cards, notched bottom bar, arrow chips, gentle motion.
- No neon, stock art, or text implying unbuilt features. Details: docs/design.md

## Workflow
Ask questions -> plan -> approval -> implement one milestone -> tests -> update docs/progress.md.
Read docs/README.md first for the index.

## Never
- Commit secrets or API keys (use gitignored local files such as secrets.properties).
- Add dependencies without explaining why.
- Claim a feature works without running it.
