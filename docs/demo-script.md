# 5-minute demo script

## Before you go on stage
1. Turn on Settings → Demo mode. Your real data stays hidden.
2. In Settings → Notification reading, make sure reading is on and Demo tools work.
3. Turn on Do Not Disturb, so personal notifications stay private.
4. Practise once with Wi-Fi off, so you know the backup path works.
5. Say that the app is showing sample data. The Home screen labels it too.

Turn Demo mode off afterwards. While it's on, new real payments go into the sample database, not your real records.

## The script
**The problem (30 seconds).** "Our phones already know what we spent and what's due, but it's scattered across notifications that disappear. Other apps would upload all of that. Memora remembers it on your phone."

**Home (60 seconds).** Show the total balance and what was deducted today. Switch Today, This week and This month. Slide Spent to Received. Point to Today's brief, the budget and the weekly chart.

**Live capture (60 seconds).** Settings → Notification reading → Demo tools. Tap **Payment**, then go Home and show the new record. Then tap **OTP code** and show it was dropped. "An OTP never gets stored. Long reference numbers are masked too." Open Records → Raw text to prove it.

**Second brain (45 seconds).** Open the star tab. Show that bills, deliveries and tickets are sorted by type. Search for something. Open Calendar and confirm a reminder Memora was unsure about. Open the To-do list and accept the suggestion from a message.

**You stay in control (30 seconds).** Tap the bell on Home. Show what was read, saved and blocked, and that blocked codes hide their sender. Tap a chat sender and star or mute them. Open Allowed apps and use the search box.

**Ask Memora (60 seconds).** Tap the lime button.
1. "How much did I spend on food this week?" The answer comes from the phone and shows its source records.
2. "Remember that my passport is in the blue drawer", then "Where is my passport?"
   Also good: "What's my balance?", "Do I have subscriptions?", "Where do I spend the most?"
3. "Remind me to call mom tomorrow at 5 pm", then "Add buy milk to my to-do".
4. If online: "Explain how UPI works". This one goes to Gemini.

**Close (15 seconds).** "Your life, remembered privately."

## If something goes wrong
| Problem | What to do |
|---|---|
| No internet, or Gemini is busy | Skip the Gemini question. Everything else works offline. Show "15% of 200" instead. |
| Popup doesn't appear | Confirm the reminder in Calendar instead. |
| A record looks wrong | Tap it to fix the category, or delete it. |

## Questions judges may ask
- **Why not use AI for everything?** Rules are free, instant, private and testable. AI is only for what rules can't do.
- **How accurate is it?** Quote only the score measured on real messages. See `app/src/test/resources/labelled_messages.tsv`.
- **Is the data encrypted?** Not yet. It's the next step, along with an app lock.
- **Can it miss payments?** Yes. Some apps don't notify when you pay, so Memora also reads bank SMS, and you can add records by hand.
