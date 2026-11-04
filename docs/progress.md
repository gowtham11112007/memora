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
- **Part of milestone 6:** delete all data, and a privacy summary.
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
- This redesign on a real phone. USB drops during transfers, so it was only rendered on the Mac.
- All extractor samples except the Paytm one are written in the style of Indian alerts, not copied from real messages.

## Known gaps
- Merchant is "Unknown" when a message does not name one.
- Period totals can go stale if the app stays open past midnight.
- General chat needs internet and a Gemini key. Offline answers cover only maths, dates, units, greetings, help and the user's own data.
- Sorting uses word rules, so some messages will land in the wrong kind or be missed.
- Not built: pause reading, retention setting, biometric lock, encrypted database, FTS5 search.

## Next
Test on the phone, then finish milestones 6 and 7.
