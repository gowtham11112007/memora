# Privacy rules (non-negotiable)

1. Only process apps the user has allowed. Default is none.
2. Drop any notification containing OTP / verification code / password / CVV / PIN keywords before anything is stored.
3. Mask long reference, card and phone numbers before storing, keeping the last 4 characters. Rows captured by the Milestone 1 build stay unmasked until the 24 h cleanup removes them.
4. Store raw text only briefly (24 h); keep the structured record.
5. Records are never sent over the network. Notification text is sent only by Smart reading. The INTERNET permission exists only for Gemini chat, Smart reading and Resend email.
   - The APK carries a Gemini key from the gitignored `secrets.properties`, so anyone with the APK could extract it. Use a key that can be replaced, and don't publish the APK. The app's screens say "Memora AI", and the privacy text and Smart reading switch say the data goes to Google's AI service.
   - Smart reading is on by default in builds with a key, and has a switch in AI and email. Nothing is sent until the user allows an app. It sends the masked sender and message from allowed apps to Google's Gemini API, where free-tier data may be used to improve Google's products. Codes, passwords, muted people, apps not allowed and bank or payment messages are never sent. Gemini's reply can only suggest a note, a reminder or a to-do. Reminders and to-dos still ask first, and it never affects money.
   - Record questions, chat commands and built-in answers run on the phone and are sent nowhere.
   - Other questions go to Google's Gemini API over HTTPS. Free-tier data may be used to improve Google's products.
   - Monthly category totals are added only if the user turns on "Share my monthly totals". They name no merchants.
   - The weekly email goes through Resend and holds totals and a reminder count, with no merchants or messages.
   - Keys are stored in private app settings, not encrypted, and never in code or Git. Anyone with a key can use its quota.
7. Second Brain keeps a short, masked first sentence of useful messages until the user deletes it. This is a deliberate exception to rule 4.
6. No API keys in code or Git. Use local, gitignored config.

## Only useful messages are kept
- A message with no money and no date is read in memory and discarded. Only the time an allowed app last posted is recorded.
- SMS and chat apps need bank-style words before anything counts as money.
- Sorting uses word rules, so a personal message that happens to use useful words could still be kept.

## Chat apps and people
- WhatsApp, Telegram and Instagram are read only if the user turns them on, like any other app.
- Chats never create money records, because a friend's "I paid 500" isn't the user's expense. A real bank alert still counts.
- A chat or personal SMS with a date or a request makes an unconfirmed reminder or to-do, stored until the user answers the popup. One with useful words, like a delivery or an exam, is also kept as a short masked note in Second Brain, at the user's request. The message's masked text stays in `raw_event` for up to 24 h. Other chats are read in memory and discarded.
- Star: messages of 12 characters or more from that person are kept as short lines in Second Brain until deleted. This is the user's choice, per person. Codes and passwords are still dropped first.
- Mute: Memora skips that person's messages after the privacy check. Only the bell page's line remains.
- Matching uses the name in the notification title, so it can miss a renamed contact or match a group with that name in it.
- Star and mute apply only to chat and SMS apps, so a name can't hide a payment alert.

## Notifications-read list (the bell)
- One line per notification from an allowed app: app, masked sender or title, what happened, and when. Never the message.
- The sender is blank for anything blocked as sensitive. Apps that aren't allowed are never listed.
- Deleted after 7 days, and by "Delete my data".

## App lock
Off by default. When on, Memora asks for the phone's screen lock when opened, and again after more than a minute away. It also hides the app in recent apps and blocks screenshots. It guards the screens only: the stored database still isn't encrypted. It needs a screen lock on the phone, and if that lock is later removed, the app stops asking so the user isn't locked out.

## Automatic reminders
Off by default. When on, a clear date from a non-chat app is set without asking, and the popup offers Undo. Chats and unclear dates still ask.

## Also
- `allowBackup=false`, so Android cloud backup never copies the database.
- Notification access is a system permission the user grants in Android settings; Memora can only open that screen.
- Android 15 may hide some one-time-code notification content from listeners; that is fine, we drop those anyway.
- Encryption at rest arrives in milestone 6.
