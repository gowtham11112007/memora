# Memora
Android app (Kotlin, Compose, Room). Reads allowed notifications, filters sensitive ones,
extracts expenses/deadlines, stores locally, answers questions from stored records.
Target device: Nothing Phone (3a), Android 16. minSdk 26, targetSdk 35, package com.pulse.app.

## Rules
- Records never leave the device. Notification text leaves only through CaptureRepository.readSmartly while Smart reading is on: masked, and never codes, muted people or money messages. Screens may say "Memora AI", but the privacy text must keep saying this goes to Google and Groq.
- INTERNET is only for AI chat, Smart reading and Resend email. Chat sends what the user types, plus category totals only if they opt in.
- Record questions, chat commands and offline answers run on the phone. The built-in keys live only in the gitignored secrets.properties, never in code or Git. The AI adds to the word rules and never replaces them, and money is decided by rules alone.
- Detected reminders and to-dos stay unconfirmed until the user says yes on the popup. Only exception: the opt-in Automatic reminders setting, for clear dates from non-chat apps, with Undo.
- Allowed-apps only (default none). Drop OTP/password/CVV/PIN messages before storing.
- Rules/regex first, LLM later and optional. Never let an LLM write or run raw SQL.
- Keep code minimal. Every regex change needs a unit test with a real sample message.
- Keep only money, reminder, to-do and useful note messages, from allowed apps. SMS and chat apps need a bank alert before anything counts as money. Everyday chat matches no rule and is dropped.
- The notifications-read log holds the sender, outcome and what was saved for 7 days, never a message that had nothing useful.
- Don't run `adb shell dumpsys notification` on the Nothing 3a: it drops the debugging connection.

## Structure (app/src/main/java/com/pulse/app)
- ui/ (Compose screens: home, calendar, ask, settings, records, theme, components)  - service/ (NotificationListenerService)
- domain/ (filter, capture gate, extractor, categoriser)  - data/ (Room entities, DAOs, CaptureRepository)
- PulseApp.kt builds the database + repository by hand (no DI library).

## Commands (run from this folder)
- Java 17 is required: export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
- Unit tests: ./gradlew testDebugUnitTest
- Render screens to app/build/shots: ./gradlew testDebugUnitTest -Pshots
- Build debug APK: ./gradlew assembleDebug  -> app/build/outputs/apk/debug/Memora.apk
- Install on phone (USB debugging on): ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/Memora.apk

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
