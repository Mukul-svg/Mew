package com.past9.phoneaos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.past9.phoneaos.data.AppDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The v3 → v4 upgrade adds indices only; every row must survive and Room must accept the schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DbMigrationTest {
    @Test fun version3DataSurvivesTheIndicesMigration() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("agent.db")
        file.parentFile?.mkdirs()
        file.delete(); java.io.File(file.path + "-wal").delete(); java.io.File(file.path + "-shm").delete()

        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        raw.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '365508688b8662942911db12621307d1')")
        raw.execSQL("CREATE TABLE chat_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, meta TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE turns (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, thread TEXT NOT NULL, json TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, kind TEXT NOT NULL, topics TEXT NOT NULL, pinned INTEGER NOT NULL, source TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE goals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, why TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, goalId INTEGER, title TEXT NOT NULL, notes TEXT NOT NULL, status TEXT NOT NULL, dueAt INTEGER, owner TEXT NOT NULL, blocker TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE triggers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, spec TEXT NOT NULL, prompt TEXT NOT NULL, enabled INTEGER NOT NULL, lastRunAt INTEGER, lastResult TEXT NOT NULL, cursor TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE notifications (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, pkg TEXT NOT NULL, app TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, postedAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE people (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sealed TEXT NOT NULL, lastLogAt INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        raw.execSQL("CREATE TABLE person_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, personId INTEGER NOT NULL, sealed TEXT NOT NULL, at INTEGER NOT NULL)")
        raw.execSQL("INSERT INTO chat_items (kind,text,meta,createdAt) VALUES ('user','kept through migration','{}',1)")
        raw.execSQL("INSERT INTO person_logs (personId,sealed,at) VALUES (1,'x',1)")
        raw.execSQL("PRAGMA user_version = 3")
        raw.close()

        val room = AppDb.open(ctx)
        try {
            assertEquals("kept through migration", room.chat().get(1)?.text)
            assertEquals(1, room.people().logs(1).size)
            val indexes = mutableSetOf<String>()
            room.openHelper.readableDatabase.query("PRAGMA index_list(chat_items)").use { c ->
                val name = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) indexes += c.getString(name)
            }
            assertTrue("chat index exists", indexes.contains("index_chat_items_kind_createdAt"))
        } finally {
            room.close()
            file.delete(); java.io.File(file.path + "-wal").delete(); java.io.File(file.path + "-shm").delete()
        }
    }
}
