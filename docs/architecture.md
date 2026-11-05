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
            Same payment (PaymentMatch) -> DUPLICATE. The existing record keeps the best name and reference.
              Same bank reference -> same. Different references -> different.
              Otherwise: same app within 2 minutes and same name, or another app within 10 minutes
              unless both names are readable and share nothing.
            Otherwise, in one transaction: insert masked raw_event, record and/or task,
            and delete raw_events older than 24 h. Confirmed tasks go to WorkManager.
  -> Screens observe the tables via Room Flow              (ui/)

Ask Memora: question -> AskEngine (fixed questions over records and tasks)
  about the user's records -> answered on the phone, with source records. No network, no SQL from user text.
  Before that, ChatCommands handles "remember", "remind me" and "find". After it, OfflineAnswers handles maths, dates, units and help.
  anything else -> GeminiClient -> gemini-flash-latest, falling back to gemini-flash-lite-latest when overloaded.
    Sends the typed question and recent Gemini turns. Never notification text or record details.

A detected date -> unconfirmed task -> ReminderPrompt popup -> Yes schedules it, To-do moves it, No deletes it.
  With Automatic reminders on, a clear date from a non-chat app is set at once and the popup offers Undo.
A message asking for something, with no date -> TaskPredictor -> unconfirmed todo -> popup: Add, Remind me tomorrow, or No.
A confirmed reminder -> an exact alarm (on time) and a WorkManager job (survives a restart). Whichever runs first calls
  ReminderDelivery and cancels the other -> notification: Done, In 10 min, Tomorrow. Repeating ones move to their next date.
Unanswered suggestions -> countNeedsAnswer -> a card on Home and a list on Calendar, so a missed popup isn't lost.
Notification buttons -> ReminderPromptReceiver -> NotificationActions (no UI, so it is tested directly).
A useful message from any allowed app -> BrainClassifier -> short masked note. Everyday chat matches no rule and is dropped.
Smart reading -> readSmartly, outside the capture lock -> SmartReader -> Gemini, then Groq. Its checked reply adds to the
  word rules and never removes what they found. Money is always decided by rules on the phone.
Chat -> AiRouter -> Groq (about 0.5 s), then Gemini, with a 25 s limit so it can't load forever.
A chat or SMS sender -> ContactMatcher -> muted: skipped. Starred: its longer messages become People notes.
Every result from an allowed app -> one line in `activity`, shown behind the bell. Sender only, kept 7 days.
Sub-pages keep a small history in MainActivity, so Back returns to whichever page opened them.
```

- **UI** (Compose + ViewModel + StateFlow): Home, Calendar, Ask, Settings, Reading and Records, in a shared PulseShell with animated page changes and a floating PulseNav. Each screen has a `*Content` composable that takes plain data, so tests can render it.
- **service**: thin. It only extracts text and forwards it.
- **domain**: pure Kotlin, unit tested without Android.
- **data**: Room (SQLite) + CaptureRepository, the single door for real and simulated notifications.
- No dependency-injection library: PulseApp holds a PulseGraph with the database and repository.

Simulated notifications use the same CaptureRepository path, so the demo exercises the real rules.
