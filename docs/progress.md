# Progress

## Done
- **Milestones 1 and 2:** capture, allowed apps, privacy filter, masking, expense extractor.
- **Milestone 3:** expense manager on Home (today, week, month totals), categories, tap to correct a category, duplicate detection, manual add, delete a record.
- **Milestone 4:** calendar, reminders from messages with confirmation for guessed dates, manual reminders, WorkManager notifications.
- **Milestone 5:** Ask Memora chat. Record questions and built-in answers run on the phone. Everything else goes to Gemini. There is no search index.
- **Second brain:** notes tab with search and pins, automatic sorting of useful notifications, reminder popups with Yes/No, chat commands (remember, remind me, find).
- **Money:** total balance, deducted and received today, monthly budget.
- **Email:** weekly totals through Resend, with a send-now button.
- **Demo polish:** demo mode with a separate database, today's brief with insights, pause reading, haptics, README, demo script, accuracy test ready for real messages.
- **Latest round:** welcome screens, subscription finder, chat answers for balance, budget, top payees and daily average, note lookups, evening summary, budget alerts, records search and date headings, CSV export. None of this has run on a real phone yet.
- **People and automation:** star and mute contacts in chat and SMS apps, bell page showing what was read and what happened, to-do list, repeating reminders, Done / In 10 min / Tomorrow on reminder alerts, predicted to-dos with buttons on the popup, optional automatic reminders, app search on the allowed-apps page, chat commands for to-dos. Tested on the Mac only.
- **Version 0.2.0:** exact-alarm reminders with a backup job, needs-your-answer card and list, new-items count on the bell, optional App lock, more student and meet-up date words, APK named `Memora.apk`. Tested on the Mac only.
- **Payment accuracy:** one payment reported by SMS and several apps counts once, using the bank reference, time and name. Sender names read from more wordings, including a name alone in the title. The balance form opens with saved values and changes only what was edited. Category circles removed from Home. Tested on the Mac only, with written samples.
- **Smart reading and Memora AI:** Gemini reads messages from allowed apps and answers chat in short replies, using a key built from the gitignored `secrets.properties`. On by default, with a switch. The request and reply format worked live from the Mac. It has not run on a phone.
- **Two AI services:** Groq answers chat first and Gemini reads messages first, chosen by testing both on 11 sample messages (Groq's Qwen 9 of 11 at 0.5 s, Gemini Lite 10 of 11 at 7 s). This is a small sample, not a measure of real accuracy. Run `./gradlew testDebugUnitTest -Plive` to test the real services.
- **Full check (0.5.0):** balance keeps updating after a bank SMS, ongoing notifications are skipped, a failed read can't crash the listener, AI reads are limited to 10 a minute, senders can be corrected, tapped notifications open the right page, popups and the bell say where each item came from and what it became, and the APK is a faster non-debuggable build. Tested on the Mac only. The release build has not been opened on a phone.
- **AI reads Second Brain:** chat answers from notes chosen for each question, with a switch. Worked live from the Mac against the real services, but not yet on a phone.
- **Sent money:** bank alerts are no longer thrown away because of a safety note or advert at the end, and more wordings are read. Tested with written samples, not the user's real SMS.
- **Allowed apps:** redesigned, with a pinned search box and filters.
- **Part of milestone 6:** delete all data, pause reading, CSV export and a privacy summary.
- **Interface:** redesigned from the best parts of all references (see design.md), with page transitions and motion. Notification reading and AI assistant have their own pages in Settings.

## Real-phone finding (Nothing 3a, Android 16)
Sending money produced no notification from any allowed app, while receiving through Paytm did. Payment apps
tend to notify only on receipt, so outgoing payments need the bank SMS, which arrives through the SMS app.
Memora now offers the SMS app as a source. From SMS apps it keeps only bank-style money messages and bill-style
reminders, and it no longer stores any message without money or a date.

## Not verified
- Whether your bank sends an SMS for each payment, and in what wording. Real SMS have not been tested yet.
- A reminder firing as a notification at its time, on a real phone.
- Demo mode switching, pause and haptics on a real phone.
- Gemini from inside the app. The request format was tested live from the Mac once, and the app code against a local server that copies that format.
- Resend for real. It is tested only against a local server, because it needs the user's key.
- The reminder popup and its Yes/No buttons on a real phone.
- App lock's unlock prompt, and exact alarms firing on time with the phone idle. Setting the alarm is tested, firing is not.
- All notification buttons on a real phone. Their logic is tested, but tapping them and a repeat rescheduling from inside its own background job are not.
- Real WhatsApp, Telegram and Instagram notifications: what they put in the title, and whether one message posts more than once, which would add extra lines to the bell page.
- This redesign on a real phone. USB drops during transfers, so it was only rendered on the Mac.
- All extractor samples except the Paytm one are written in the style of Indian alerts, not copied from real messages.

## Known gaps
- Merchant is "Unknown" when a message does not name one.
- Period totals can go stale if the app stays open past midnight.
- General chat needs internet and a Gemini key. Offline answers cover only maths, dates, units, greetings, help and the user's own data.
- Sorting uses word rules, so some messages will land in the wrong kind or be missed. The user reports Second Brain isn't accurate on a real phone, and examples are still needed.
- Records counted more than once before this fix stay until deleted by hand.
- Two equal payments through different apps within 10 minutes, with no references and unclear names, are counted as one.
- To-do prediction is also word rules. It will miss requests and sometimes offer the wrong thing, which is why it asks first.
- Star and mute match the name in the notification, not a phone number or contact.
- Exact alarms are lost on restart, so a reminder due then comes from the backup job and may be a little late. Aggressive battery settings can also hold alerts back. Force-stopping the app stops both.
- The new date words (assignment, viva, quiz and others) are tested only with written samples, not real messages.
- Not built: pause reading, retention setting, biometric lock, encrypted database, FTS5 search.

## Next
Test on the phone, then finish milestones 6 and 7.
