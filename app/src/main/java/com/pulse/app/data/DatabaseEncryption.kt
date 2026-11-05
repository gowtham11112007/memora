package com.pulse.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.DataInputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Tells plain and encrypted database files apart by their first bytes. */
object DbFile {
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun isPlain(file: File) = header(file)?.contentEquals(PLAIN_HEADER) == true

    fun isEncrypted(file: File) = header(file)?.contentEquals(PLAIN_HEADER) == false

    /** Null when the file is missing or too short to hold anything yet. */
    private fun header(file: File): ByteArray? = runCatching {
        ByteArray(PLAIN_HEADER.size).also { bytes -> DataInputStream(file.inputStream()).use { it.readFully(bytes) } }
    }.getOrNull()

    /** SQLCipher's form for a key that's already random, which skips its slow password step on every open. */
    fun rawKey(key: ByteArray) = key.joinToString("", "x'", "'") { "%02x".format(it) }
}

/** Protects the database key while it's saved. An interface so tests can run without the phone's secure hardware. */
interface KeySealer {
    fun seal(secret: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * Seals with a key that Android Keystore creates and never lets out. It doesn't ask for a fingerprint,
 * because Memora has to save notifications in the background while the phone is locked.
 */
class KeystoreSealer(private val alias: String = "memora_database") : KeySealer {
    override fun seal(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        return cipher.iv + cipher.doFinal(secret)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}

/** Makes one random database key per install and keeps it sealed in the app's private settings. */
class DatabaseKey(private val prefs: SharedPreferences, private val sealer: KeySealer) {
    /** Never replaces a saved key, even one it can't read right now: a new key would make the data unreadable. */
    @Synchronized
    fun load(): ByteArray {
        prefs.getString(SEALED, null)?.let { return sealer.open(Base64.getDecoder().decode(it)) }
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val sealed = Base64.getEncoder().encodeToString(sealer.seal(key))
        // Written to disk before anything is encrypted with it, so a crash can't strand encrypted data.
        check(prefs.edit().putString(SEALED, sealed).commit()) { "Couldn't save the database key" }
        return key
    }

    private companion object {
        const val SEALED = "sealed"
        const val KEY_BYTES = 32
    }
}

/**
 * Replaces a plain database with an encrypted copy. The app can be killed at any point, so the original
 * file is only renamed, and is deleted once the encrypted one has really opened.
 */
class DatabaseSwap(private val db: File) {
    private val copy = File(db.path + ".encrypting")
    private val old = File(db.path + ".plain")

    /** Run first. The copy is only ever left beside a missing database after it passed its checks. */
    fun finishInterrupted() {
        if (!db.exists() && old.exists() && !(copy.exists() && copy.renameTo(db))) move(old, db)
        delete(copy)
    }

    /** True if the encrypted copy is now in place. False means the original is still there, unchanged. */
    fun encrypt(write: (plain: File, target: File) -> Unit): Boolean {
        val swapped = runCatching {
            delete(copy)
            write(db, copy)
            check(DbFile.isEncrypted(copy)) { "The copy isn't encrypted" }
            check(move(db, old)) { "Couldn't move the original" }
            if (!copy.renameTo(db)) {
                move(old, db)
                error("Couldn't move the copy")
            }
        }.isSuccess
        delete(copy)
        return swapped
    }

    /** Puts the original back after a swap. False if there's no original to go back to. */
    fun restoreOld(): Boolean {
        if (!old.exists()) return false
        delete(db)
        return move(old, db)
    }

    fun deleteOld() = delete(old)

    fun deleteCurrent() = delete(db)

    /** SQLite's write-ahead log and journal belong to the file beside them, so they travel with it. */
    private fun move(from: File, to: File): Boolean {
        SIDECARS.forEach { File(from.path + it).renameTo(File(to.path + it)) }
        return from.renameTo(to)
    }

    private fun delete(file: File) {
        file.delete()
        SIDECARS.forEach { File(file.path + it).delete() }
    }

    private companion object {
        val SIDECARS = listOf("-wal", "-shm", "-journal")
    }
}

/** The parts that need the real encryption library, which only runs on Android, so tests can swap them. */
interface DatabaseEngine {
    /** Throws if encryption can't work on this phone. */
    fun key(): ByteArray

    /** Writes an encrypted copy of [plain] to [target] and checks it. Throws if anything is wrong. */
    fun encrypt(plain: File, target: File, key: ByteArray)

    fun openHelper(configuration: SupportSQLiteOpenHelper.Configuration, key: ByteArray): SupportSQLiteOpenHelper
}

class SqlCipherEngine(context: Context) : DatabaseEngine {
    private val keys = DatabaseKey(context.getSharedPreferences("database_key", Context.MODE_PRIVATE), KeystoreSealer())

    override fun key(): ByteArray {
        System.loadLibrary("sqlcipher")
        return keys.load()
    }

    override fun encrypt(plain: File, target: File, key: ByteArray) {
        val raw = DbFile.rawKey(key)
        val (version, rows) = SQLiteDatabase.openDatabase(plain.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val before = db.version to rowCounts(db)
            db.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf<Any>(target.path, raw))
            db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            db.rawExecSQL("PRAGMA encrypted.user_version = ${before.first}")
            db.execSQL("DETACH DATABASE encrypted")
            before
        }
        // Reopened from scratch with the key, so a bad copy stops here, before the original is touched.
        val keyBytes = raw.toByteArray(Charsets.US_ASCII)
        SQLiteDatabase.openDatabase(target.path, keyBytes, null, SQLiteDatabase.OPEN_READONLY, null).use { db ->
            check(db.version == version) { "The copy has the wrong version" }
            check(rowCounts(db) == rows) { "The copy has different rows" }
        }
    }

    override fun openHelper(configuration: SupportSQLiteOpenHelper.Configuration, key: ByteArray) =
        SupportOpenHelperFactory(DbFile.rawKey(key).toByteArray(Charsets.US_ASCII)).create(configuration)

    private fun rowCounts(db: SQLiteDatabase): Map<String, Long> {
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            emptyArray<String>(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return tables.associateWith { table ->
            db.rawQuery("SELECT count(*) FROM \"$table\"", emptyArray<String>()).use {
                it.moveToFirst()
                it.getLong(0)
            }
        }
    }
}

class DatabaseHelperFactory(private val engine: DatabaseEngine) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        EncryptedFirstHelper(configuration, engine)
}

/**
 * Decides how to open the database on first use. Room only does that away from the main thread, so a
 * conversion can't freeze a screen.
 *
 * If encryption fails, data always wins: Memora carries on with the plain file and tries again next start.
 * An encrypted file is never deleted here, because a failure might only mean the key can't be read right now.
 */
private class EncryptedFirstHelper(
    private val config: SupportSQLiteOpenHelper.Configuration,
    private val engine: DatabaseEngine,
) : SupportSQLiteOpenHelper {
    private var walEnabled = false
    private var chosen: SupportSQLiteOpenHelper? = null

    override val databaseName: String? get() = config.name
    override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase
    override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase

    @Synchronized
    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        walEnabled = enabled
        chosen?.setWriteAheadLoggingEnabled(enabled)
    }

    @Synchronized
    override fun close() {
        chosen?.close()
    }

    @Synchronized
    private fun helper(): SupportSQLiteOpenHelper = chosen ?: choose().also { chosen = it }

    private fun choose(): SupportSQLiteOpenHelper {
        val file = config.context.getDatabasePath(config.name)
        file.parentFile?.mkdirs()
        val swap = DatabaseSwap(file)
        swap.finishInterrupted()

        val key = runCatching { engine.key() }.getOrNull()
        if (key == null) {
            check(!DbFile.isEncrypted(file)) { "The database is encrypted and its key can't be read" }
            return plainHelper()
        }
        if (DbFile.isPlain(file) && !swap.encrypt { plain, target -> engine.encrypt(plain, target, key) }) {
            return plainHelper()
        }

        val brandNew = file.length() == 0L
        return runCatching {
            engine.openHelper(config, key).apply {
                setWriteAheadLoggingEnabled(walEnabled)
                writableDatabase // Opens it now, so a failure is handled here and not on some later query.
            }
        }.onSuccess { swap.deleteOld() }.getOrElse { failure ->
            when {
                swap.restoreOld() -> plainHelper()
                brandNew -> {
                    swap.deleteCurrent()
                    plainHelper()
                }
                else -> throw failure
            }
        }
    }

    private fun plainHelper() =
        FrameworkSQLiteOpenHelperFactory().create(config).apply { setWriteAheadLoggingEnabled(walEnabled) }
}
