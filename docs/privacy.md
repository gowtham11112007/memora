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

## Also
- `allowBackup=false`, so Android cloud backup never copies the database.
- Notification access is a system permission the user grants in Android settings; Memora can only open that screen.
- Android 15 may hide some one-time-code notification content from listeners; that is fine, we drop those anyway.
- Encryption at rest arrives in milestone 6.
