# Privacy rules (non-negotiable)

1. Only process apps the user has allowed. Default is none.
2. Drop any notification containing OTP / verification code / password / CVV / PIN keywords before anything is stored.
3. Mask long reference, card and phone numbers before storing, keeping the last 4 characters. Rows captured by the Milestone 1 build stay unmasked until the 24 h cleanup removes them.
4. Store raw text only briefly (24 h); keep the structured record.
5. Notification text and records are never sent over the network. The INTERNET permission exists only for optional Gemini chat and Resend email.
   - Record questions, chat commands and built-in answers run on the phone and are sent nowhere.
   - Other questions go to Google's Gemini API over HTTPS. Free-tier data may be used to improve Google's products.
   - Monthly category totals are added only if the user turns on "Share my monthly totals". They name no merchants.
   - The weekly email goes through Resend and holds totals and a reminder count, with no merchants or messages.
   - Keys are stored in private app settings, not encrypted, and never in code or Git. Anyone with a key can use its quota.
7. Second Brain keeps a short, masked first sentence of useful messages until the user deletes it. This is a deliberate exception to rule 4.
6. No API keys in code or Git. Use local, gitignored config.

## Only useful messages are kept
- A message with no money and no date is read in memory and discarded. Only the time an allowed app last posted is recorded.
- SMS apps also carry personal chats, so they get a stricter check: bank-style words for money, and bill-style words for reminders.
- A personal chat that happens to use those words could still be kept. The check is a rule, not a guarantee.

## Chat apps and people
- WhatsApp, Telegram and Instagram are read only if the user turns them on, like any other app.
- Chats never create money records, because a friend's "I paid 500" isn't the user's expense. A real bank alert still counts.
- A chat with a date or a request makes an unconfirmed reminder or to-do, stored until the user answers the popup. The message's masked text also stays in `raw_event` for up to 24 h. Other chats are read in memory and discarded.
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
