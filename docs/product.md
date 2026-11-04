# Product

**Memora** (formerly PULSE). The internal package stays `com.pulse.app`, so updates keep existing data. Tagline: "Your life, remembered privately."

An Android app that reads notifications from apps the user approves, filters sensitive ones,
extracts structured facts (expenses, deadlines), stores them on the phone, and later lets the
user ask questions about their own records. Built for a hackathon (theme: day-to-day life),
so a smooth live demo matters more than feature count.

## MVP (one flow only)
notification -> privacy filter -> rule-based extraction (amount, merchant, expense/income)
-> save to Room -> Money screen with a total.

## Milestones
1. Foundation + capture (this one): listener, allowed apps, raw_event storage, simulate button.
2. Privacy filter + extractor (regex, 20+ real Indian payment messages).
3. Money screen, categories, duplicate detection.
4. Reminders. 5. Search + Ask Memora. 6. Privacy Center + encryption. 7. Demo polish.

Built so far and what remains: see progress.md. The UI must not imply anything that is not built.
