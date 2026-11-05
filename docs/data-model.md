# Data model (Room, database version 8)

The database file is encrypted with SQLCipher. The tables below are unchanged by that. See [privacy.md](privacy.md).

`MIGRATION_7_8` adds `chat_turn`: `id`, `conversation` (indexed), `question`, `answer`, `source`, `notes`, `at` (indexed). Saved chats, kept until deleted, up to 500 turns.

`MIGRATION_6_7` adds `task.origin`, `todo.origin` and `activity.detail`: who a suggestion came from, and what a notification became.

`MIGRATION_5_6` adds `record.reference`: the last 4 digits of the bank's reference, used to spot one payment reported by several apps.

## contact_rule (added by `MIGRATION_4_5`)
`name` (TEXT PK, lowercase), `label`, `starred`. True means starred, false means muted.

## activity (added by `MIGRATION_4_5`)
`id`, `packageName`, `title` (masked sender, blank if sensitive), `outcome` (a CaptureDecision name), `at` (indexed). Never the message. Deleted after 7 days.

## todo (added by `MIGRATION_4_5`)
`id`, `text`, `done`, `confirmed`, `createdAt` (indexed), `source`. Unconfirmed means suggested from a message and waiting for an answer.

## note (added by `MIGRATION_3_4`)
`id`, `text`, `kind` (a NoteKind name), `createdAt` (indexed), `pinned`, `source`. Typed by the user, or a short masked line from a message.

Balance, budget and keys live in private SharedPreferences, not in Room.

`MIGRATION_1_2` adds `record` and `drop_log`. `MIGRATION_2_3` adds `merchant_rule`, `task` and `app_seen`.
Both keep existing data, and tests prove they match the entities.

## merchant_rule
`merchant` (TEXT PK, lowercase), `category` (TEXT). The user's choice beats the built-in dictionary.

## task
`id`, `title`, `dueAt` (indexed), `confidence`, `confirmed`, `done`, `source`, `repeat` (NONE, DAILY, WEEKLY or MONTHLY). Only confirmed tasks are scheduled.

## app_seen
`packageName` (TEXT PK), `lastSeenAt`, `seenCount`. Time and count only, never content.

## raw_event
| column | type | note |
|---|---|---|
| id | INTEGER PK autoincrement | |
| packageName | TEXT | source app |
| title | TEXT | notification title |
| text | TEXT | big text, else normal text |
| postedAt | INTEGER | when the source app posted it (epoch ms) |
| capturedAt | INTEGER | when Memora stored it; retention counts from here |

Rows older than 24 h are deleted on every capture (`Retention.RAW_EVENT_MS`).

## app_permission
| column | type | note |
|---|---|---|
| packageName | TEXT PK | |
| label | TEXT | display name at the time of toggling |
| allowed | INTEGER (bool) | no row = not allowed |

## record
| column | type | note |
|---|---|---|
| id | INTEGER PK autoincrement | |
| packageName | TEXT | source app |
| amountPaise | INTEGER | money in paise, never a float |
| merchant | TEXT, nullable | masked; null if the rules could not find one |
| type | TEXT | EXPENSE or INCOME |
| occurredAt | INTEGER, indexed | when the source app posted it |

Holds no raw message text and is kept after the raw text expires.

## drop_log
| column | type | note |
|---|---|---|
| id | INTEGER PK autoincrement | |
| droppedAt | INTEGER | the only thing recorded about a blocked message |

Powers the "Codes blocked" count. It stores no content and no source app.

## Planned (not built)
FTS5 index (milestone 5).
