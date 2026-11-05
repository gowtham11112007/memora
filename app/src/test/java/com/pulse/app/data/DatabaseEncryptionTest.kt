package com.pulse.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The encryption library only runs on Android, so these can't prove real encryption works. They do prove
 * what happens around it, above all that a failure at any step leaves the user's data readable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseEncryptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val garbage = Random(7).nextBytes(8192)

    private fun file(name: String) = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }

    private fun dir(name: String) = File(context.cacheDir, name).also { it.mkdirs() }

    private fun build(name: String, engine: DatabaseEngine) = Room.databaseBuilder(context, PulseDatabase::class.java, name)
        .openHelperFactory(DatabaseHelperFactory(engine))
        .allowMainThreadQueries()
        .build()

    /** A database as version 0.8.0 left it: plain, with one note in it. */
    private fun seedPlain(name: String) = runBlocking {
        val db = Room.databaseBuilder(context, PulseDatabase::class.java, name).allowMainThreadQueries().build()
        db.noteDao().insert(Note(text = "kept", kind = "NOTE", createdAt = 1, source = "manual"))
        db.close()
    }

    private fun notes(db: PulseDatabase) = runBlocking { db.noteDao().all().map { it.text } }

    private fun leftovers(name: String) = file(name).parentFile!!.list()!!.filter { it.endsWith(".plain") || it.endsWith(".encrypting") }

    private inner class FakeEngine(
        val keyFails: Boolean = false,
        val encryptFails: Boolean = false,
        val openFails: Boolean = false,
    ) : DatabaseEngine {
        override fun key(): ByteArray = if (keyFails) error("No key") else ByteArray(32) { 1 }

        override fun encrypt(plain: File, target: File, key: ByteArray) {
            if (encryptFails) error("Disk full")
            target.writeBytes(garbage)
        }

        override fun openHelper(configuration: SupportSQLiteOpenHelper.Configuration, key: ByteArray): SupportSQLiteOpenHelper =
            if (openFails) {
                object : SupportSQLiteOpenHelper {
                    override val databaseName: String? = configuration.name
                    override val writableDatabase: SupportSQLiteDatabase get() = error("Can't open")
                    override val readableDatabase: SupportSQLiteDatabase get() = error("Can't open")
                    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit
                    override fun close() = Unit
                }
            } else {
                // Stands in for an encrypted database that opened fine.
                FrameworkSQLiteOpenHelperFactory().create(
                    SupportSQLiteOpenHelper.Configuration(configuration.context, "stand-in.db", configuration.callback),
                )
            }
    }

    // What the user's data does when something goes wrong.

    @Test
    fun `without the native library the app still works on a plain database`() = runBlocking {
        val db = build("no-library.db", SqlCipherEngine(context))
        db.noteDao().insert(Note(text = "saved", kind = "NOTE", createdAt = 1, source = "manual"))
        assertEquals(listOf("saved"), notes(db))
        db.close()
        assertTrue(DbFile.isPlain(file("no-library.db")))
    }

    @Test
    fun `existing data stays readable when encrypting fails`() {
        seedPlain("copy-fails.db")
        val db = build("copy-fails.db", FakeEngine(encryptFails = true))
        assertEquals(listOf("kept"), notes(db))
        db.close()
        assertTrue(DbFile.isPlain(file("copy-fails.db")))
        assertEquals(emptyList<String>(), leftovers("copy-fails.db"))
    }

    @Test
    fun `existing data is put back when the encrypted copy will not open`() {
        seedPlain("open-fails.db")
        val db = build("open-fails.db", FakeEngine(openFails = true))
        assertEquals(listOf("kept"), notes(db))
        db.close()
        assertTrue(DbFile.isPlain(file("open-fails.db")))
        assertEquals(emptyList<String>(), leftovers("open-fails.db"))
    }

    @Test
    fun `a new database falls back to plain when encryption will not open`() = runBlocking {
        val db = build("new-fails.db", FakeEngine(openFails = true))
        db.noteDao().insert(Note(text = "saved", kind = "NOTE", createdAt = 1, source = "manual"))
        assertEquals(listOf("saved"), notes(db))
        db.close()
    }

    @Test
    fun `an encrypted database is never touched when its key can't be read`() {
        val file = file("no-key.db").apply { writeBytes(garbage) }
        val db = build("no-key.db", FakeEngine(keyFails = true))
        assertThrows(Throwable::class.java) { notes(db) }
        assertArrayEquals(garbage, file.readBytes())
    }

    @Test
    fun `an encrypted database that will not open is never deleted`() {
        val file = file("stuck.db").apply { writeBytes(garbage) }
        val db = build("stuck.db", FakeEngine(openFails = true))
        assertThrows(Throwable::class.java) { notes(db) }
        assertArrayEquals(garbage, file.readBytes())
    }

    @Test
    fun `the plain copy is deleted once the encrypted database has opened`() {
        seedPlain("works.db")
        val db = build("works.db", FakeEngine())
        notes(db)
        db.close()
        assertArrayEquals(garbage, file("works.db").readBytes())
        assertEquals(emptyList<String>(), leftovers("works.db"))
    }

    // The swap on its own, including being killed halfway.

    @Test
    fun `a swap keeps the original until told otherwise`() {
        val dir = dir("swap-ok")
        val db = File(dir, "x.db").apply { writeBytes(PLAIN) }
        File(dir, "x.db-wal").writeText("log")
        val swap = DatabaseSwap(db)

        assertTrue(swap.encrypt { _, target -> target.writeBytes(garbage) })

        assertArrayEquals(garbage, db.readBytes())
        assertArrayEquals(PLAIN, File(dir, "x.db.plain").readBytes())
        assertEquals("log", File(dir, "x.db.plain-wal").readText())
        assertFalse("A plain log must not sit beside the encrypted file", File(dir, "x.db-wal").exists())
        assertFalse(File(dir, "x.db.encrypting").exists())

        swap.deleteOld()
        assertFalse(File(dir, "x.db.plain").exists())
        assertFalse(File(dir, "x.db.plain-wal").exists())
    }

    @Test
    fun `a copy that isn't encrypted is refused`() {
        val dir = dir("swap-plain-copy")
        val db = File(dir, "x.db").apply { writeBytes(PLAIN) }

        assertFalse(DatabaseSwap(db).encrypt { plain, target -> plain.copyTo(target) })

        assertArrayEquals(PLAIN, db.readBytes())
        assertEquals(listOf("x.db"), dir.list()!!.toList())
    }

    @Test
    fun `restoring brings back the original and its log`() {
        val dir = dir("swap-restore")
        val db = File(dir, "x.db").apply { writeBytes(PLAIN) }
        File(dir, "x.db-wal").writeText("log")
        val swap = DatabaseSwap(db)
        swap.encrypt { _, target -> target.writeBytes(garbage) }

        assertTrue(swap.restoreOld())

        assertArrayEquals(PLAIN, db.readBytes())
        assertEquals("log", File(dir, "x.db-wal").readText())
        assertFalse(swap.restoreOld())
    }

    @Test
    fun `killed between the two renames, the checked copy is used`() {
        val dir = dir("swap-killed")
        File(dir, "x.db.plain").writeBytes(PLAIN)
        File(dir, "x.db.encrypting").writeBytes(garbage)
        val db = File(dir, "x.db")

        DatabaseSwap(db).finishInterrupted()

        assertArrayEquals(garbage, db.readBytes())
        assertArrayEquals(PLAIN, File(dir, "x.db.plain").readBytes())
    }

    @Test
    fun `killed while restoring, the original comes back`() {
        val dir = dir("swap-killed-restore")
        File(dir, "x.db.plain").writeBytes(PLAIN)
        val db = File(dir, "x.db")

        DatabaseSwap(db).finishInterrupted()

        assertArrayEquals(PLAIN, db.readBytes())
    }

    @Test
    fun `killed while copying, the half-written copy is thrown away`() {
        val dir = dir("swap-killed-copy")
        val db = File(dir, "x.db").apply { writeBytes(PLAIN) }
        File(dir, "x.db.encrypting").writeBytes(garbage)

        DatabaseSwap(db).finishInterrupted()

        assertArrayEquals(PLAIN, db.readBytes())
        assertEquals(listOf("x.db"), dir.list()!!.toList())
    }

    // Telling files apart, which the status in Settings relies on.

    @Test
    fun `a real SQLite file counts as plain`() {
        seedPlain("real.db")
        assertTrue(DbFile.isPlain(file("real.db")))
        assertFalse(DbFile.isEncrypted(file("real.db")))
    }

    @Test
    fun `random bytes count as encrypted`() {
        val file = file("random.db").apply { writeBytes(garbage) }
        assertFalse(DbFile.isPlain(file))
        assertTrue(DbFile.isEncrypted(file))
    }

    @Test
    fun `missing or empty files are neither`() {
        val missing = file("missing.db")
        val empty = file("empty.db").apply { writeBytes(ByteArray(0)) }
        for (file in listOf(missing, empty)) {
            assertFalse(DbFile.isPlain(file))
            assertFalse(DbFile.isEncrypted(file))
        }
    }

    @Test
    fun `the raw key is in the form SQLCipher expects`() {
        assertEquals("x'ff0010'", DbFile.rawKey(byteArrayOf(-1, 0, 16)))
        // 3 + 64: SQLCipher only treats it as a raw key at exactly this length.
        assertEquals(67, DbFile.rawKey(ByteArray(32)).length)
    }

    // The key.

    private object FlipSealer : KeySealer {
        override fun seal(secret: ByteArray) = secret.map { (it + 1).toByte() }.toByteArray()
        override fun open(sealed: ByteArray) = sealed.map { (it - 1).toByte() }.toByteArray()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun `the same key comes back every time and is saved sealed`() {
        val prefs = prefs("key-same")
        val first = DatabaseKey(prefs, FlipSealer).load()
        assertEquals(32, first.size)
        assertArrayEquals(first, DatabaseKey(prefs, FlipSealer).load())
        val saved = java.util.Base64.getDecoder().decode(prefs.getString("sealed", null))
        assertFalse(first.contentEquals(saved))
    }

    @Test
    fun `each install gets its own key`() {
        val one = DatabaseKey(prefs("key-a"), FlipSealer).load()
        val two = DatabaseKey(prefs("key-b"), FlipSealer).load()
        assertNotEquals(one.toList(), two.toList())
    }

    @Test
    fun `a saved key that can't be read is never replaced`() {
        val prefs = prefs("key-stuck")
        DatabaseKey(prefs, FlipSealer).load()
        val saved = prefs.getString("sealed", null)
        val broken = object : KeySealer {
            override fun seal(secret: ByteArray) = secret
            override fun open(sealed: ByteArray): ByteArray = error("Keystore is unavailable")
        }

        assertThrows(IllegalStateException::class.java) { DatabaseKey(prefs, broken).load() }

        assertEquals(saved, prefs.getString("sealed", null))
    }

    private companion object {
        val PLAIN = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(100)
    }
}
