package com.pulse.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RawEvent::class, AppPermission::class, MoneyRecord::class, DropLog::class,
        MerchantRule::class, Reminder::class, AppSeen::class, Note::class,
        ContactRule::class, ActivityEntry::class, Todo::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun rawEventDao(): RawEventDao
    abstract fun appPermissionDao(): AppPermissionDao
    abstract fun moneyRecordDao(): MoneyRecordDao
    abstract fun dropLogDao(): DropLogDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun reminderDao(): ReminderDao
    abstract fun appSeenDao(): AppSeenDao
    abstract fun noteDao(): NoteDao
    abstract fun contactRuleDao(): ContactRuleDao
    abstract fun activityDao(): ActivityDao
    abstract fun todoDao(): TodoDao
}

/** Remembers who each suggestion came from, and what each notification became. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `task` ADD COLUMN `origin` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `todo` ADD COLUMN `origin` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `activity` ADD COLUMN `detail` TEXT NOT NULL DEFAULT ''")
    }
}

/** Adds the bank reference, so one payment reported by several apps is counted once. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `record` ADD COLUMN `reference` TEXT")
    }
}

/** Adds people rules, the notifications-read log, to-dos and repeating reminders. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `contact_rule` (`name` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`starred` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`packageName` TEXT NOT NULL, `title` TEXT NOT NULL, `outcome` TEXT NOT NULL, `at` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_at` ON `activity` (`at`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `todo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
                "`done` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `source` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_createdAt` ON `todo` (`createdAt`)")
        db.execSQL("ALTER TABLE `task` ADD COLUMN `repeat` TEXT NOT NULL DEFAULT 'NONE'")
    }
}

/** Adds the Second Brain notes. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `note` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `source` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_createdAt` ON `note` (`createdAt`)")
    }
}

/** Adds the Milestone 2 tables and keeps existing data, so updating the app loses nothing. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`packageName` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `merchant` TEXT, " +
                "`type` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_record_occurredAt` ON `record` (`occurredAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `drop_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`droppedAt` INTEGER NOT NULL)",
        )
    }
}

/** Adds categories, reminders and the last-read time per app. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `merchant_rule` (`merchant` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                "PRIMARY KEY(`merchant`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `task` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `confidence` REAL NOT NULL, " +
                "`confirmed` INTEGER NOT NULL, `done` INTEGER NOT NULL, `source` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_dueAt` ON `task` (`dueAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_seen` (`packageName` TEXT NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                "`seenCount` INTEGER NOT NULL, PRIMARY KEY(`packageName`))",
        )
    }
}
