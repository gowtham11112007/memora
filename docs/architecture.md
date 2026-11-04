# Architecture

```
Any app posts a notification
  -> PulseNotificationListener.onNotificationPosted        (service/)
  -> CaptureRepository.capture(pkg, title, text, postedAt) (data/)
       1. CaptureGate.decide(isAllowed, title, text)       (domain/)
            not allowed      -> IGNORED_NOT_ALLOWED (content never inspected)
            empty            -> DROPPED_EMPTY
            OTP/password/etc -> DROPPED_SENSITIVE (PrivacyFilter)
            else             -> STORE
       2. DROPPED_SENSITIVE: add a time-only row to drop_log
       3. STORE: ExpenseExtractor and DeadlineExtractor read the original text in memory.
            Neither found anything -> NOTHING_USEFUL, nothing kept
            Same payment within 2 minutes -> DUPLICATE, nothing kept
            Otherwise, in one transaction: insert masked raw_event, record and/or task,
            and delete raw_events older than 24 h. Confirmed tasks go to WorkManager.
  -> Screens observe the tables via Room Flow              (ui/)

Ask Memora: question -> AskEngine (fixed questions over records and tasks)
  about the user's records -> answered on the phone, with source records. No network, no SQL from user text.
  Before that, ChatCommands handles "remember", "remind me" and "find". After it, OfflineAnswers handles maths, dates, units and help.
  anything else -> GeminiClient -> gemini-flash-latest, falling back to gemini-flash-lite-latest when overloaded.
    Sends the typed question and recent Gemini turns. Never notification text or record details.

A detected date -> unconfirmed task -> ReminderPrompt popup -> Yes schedules it, No deletes it.
A useful message -> BrainClassifier -> short masked note. Personal SMS are excluded by a business-sender check.
```

- **UI** (Compose + ViewModel + StateFlow): Home, Calendar, Ask, Settings, Reading and Records, in a shared PulseShell with animated page changes and a floating PulseNav. Each screen has a `*Content` composable that takes plain data, so tests can render it.
- **service**: thin. It only extracts text and forwards it.
- **domain**: pure Kotlin, unit tested without Android.
- **data**: Room (SQLite) + CaptureRepository, the single door for real and simulated notifications.
- No dependency-injection library: PulseApp holds a PulseGraph with the database and repository.

Simulated notifications use the same CaptureRepository path, so the demo exercises the real rules.
