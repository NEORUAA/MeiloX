package com.ljyh.mei.parasite

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.data.model.room.AccountPlaylist
import com.ljyh.mei.data.model.room.Playlist
import com.ljyh.mei.data.model.room.PlaylistType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Uses disposable module-owned markers; never reads host preferences or account data. */
internal object ModuleStorageProbe {
    private const val NAME = "__runtime_storage_probe__"

    fun run(context: Context, host: Context, report: (String) -> Unit) {
        try {
            check(context.packageName == host.packageName)
            check(context.applicationContext === context)
            check(context.resources !== host.resources)
            check(context.classLoader === ModuleStorageProbe::class.java.classLoader)
            check(context.filesDir != host.filesDir && context.cacheDir != host.cacheDir)
            check(context.codeCacheDir != host.codeCacheDir && context.noBackupFilesDir != host.noBackupFilesDir)
            check(context.getDir(NAME, Context.MODE_PRIVATE).parentFile == context.dataDir)
            context.getDir(NAME, Context.MODE_PRIVATE).delete()
            context.getExternalFilesDir(null)?.let { root ->
                check(root != host.getExternalFilesDir(null))
                check(context.getExternalFilesDir("Music") == File(root, "Music"))
            }
            (context.getExternalFilesDirs(null) + context.externalCacheDirs + context.externalMediaDirs + context.obbDirs)
                .filterNotNull().forEach { check(it.name == ModuleStorage.NAMESPACE) }
            val originalFileExists = host.getFileStreamPath(NAME).exists()
            val originalDatabaseExists = host.getDatabasePath(NAME).exists()
            try {
                context.openFileOutput(NAME, Context.MODE_PRIVATE).use { it.write(1) }
                context.openFileOutput(NAME, Context.MODE_APPEND).use { it.write(2) }
                check(context.openFileInput(NAME).use { it.readBytes().contentEquals(byteArrayOf(1, 2)) })
                check(NAME in context.fileList())
                val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                check(preferences.edit().putBoolean("marker", true).commit())
                check(context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("marker", false))
                context.openOrCreateDatabase(NAME, Context.MODE_PRIVATE, null).use { database ->
                    database.execSQL("CREATE TABLE marker (value INTEGER NOT NULL)")
                    database.execSQL("INSERT INTO marker VALUES (1)")
                    database.rawQuery("SELECT value FROM marker", null).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getInt(0) == 1)
                    }
                    check(File(database.path).canonicalFile == context.getDatabasePath(NAME).canonicalFile)
                }
                check(NAME in context.databaseList())
                check(host.getFileStreamPath(NAME).exists() == originalFileExists)
                check(host.getDatabasePath(NAME).exists() == originalDatabaseExists)
                val configured = context.createConfigurationContext(Configuration(context.resources.configuration))
                check(configured.applicationContext === context)
                check(configured.filesDir == context.filesDir)
                check(configured.classLoader === context.classLoader)
                val protected = context.createDeviceProtectedStorageContext()
                check(protected.isDeviceProtectedStorage)
                check(protected.filesDir != context.filesDir)
                check(protected.applicationContext.filesDir == protected.filesDir)
                report("runtime_storage file=true preferences=true sqlite=true derived_contexts=true external_namespaces=true")
            } finally {
                context.deleteFile(NAME)
                context.deleteSharedPreferences(NAME)
                context.deleteDatabase(NAME)
            }
            verifyRoom(context)
            report("runtime_storage room_schema=true account_library=true migration_17_18=true")
            verifyDataStore(context)
            report("runtime_storage datastore=true")
        } catch (error: Throwable) {
            report("runtime_storage_failed type=${error.javaClass.name}")
        }
    }

    private fun verifyRoom(context: Context) {
        val name = "${NAME}_room"
        var database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table'").use {
                check(it.moveToFirst() && it.getInt(0) > 1)
            }
            val shared = Playlist("shared", "Shared", "", "creator", "Creator", "", 1,
                lastPlayTime = 10, localPlayCount = 4)
            runBlocking { database.playlistDao().insertPlaylist(shared) }
            database.close()
            // Recreate the previous schema without exporting any real module database.
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use {
                it.execSQL("DROP TABLE account_playlist")
                it.version = 17
            }
            database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_17_18).build()
            check(database.openHelper.writableDatabase.version == 18)
            runBlocking {
                val dao = database.playlistDao()
                check(dao.getPlaylist("shared")?.localPlayCount == 4)
                val local = shared.copy(id = "local", type = PlaylistType.USER)
                dao.insertPlaylist(local)
                dao.insertPlaylist(shared.copy(id = "unowned"))
                dao.replaceAccountPlaylists("account-a", listOf(
                    AccountPlaylist(shared, false), AccountPlaylist(shared.copy(id = "liked-a"), true),
                )) {}
                dao.replaceAccountPlaylists("account-b", listOf(
                    AccountPlaylist(shared, true), AccountPlaylist(shared.copy(id = "only-b"), false),
                )) {}
                dao.touchPlaylist("shared", 20)
                dao.replaceAccountPlaylists("account-a", listOf(AccountPlaylist(shared.copy(title = "Updated"), false))) {}
                val updated = checkNotNull(dao.getPlaylist("shared"))
                check(updated.title == "Updated" && updated.localPlayCount == 5 && updated.lastPlayTime == 20L)
                check(dao.getAccountPlaylists("account-a").first().map { it.playlist.id }.toSet() == setOf("local", "shared"))
                val otherAccount = dao.getAccountPlaylists("account-b").first()
                check(otherAccount.map { it.playlist.id }.toSet() == setOf("local", "shared", "only-b"))
                check(otherAccount.single { it.playlist.id == "shared" }.isLiked)
                var checks = 0
                val canceled = runCatching {
                    dao.replaceAccountPlaylists("account-a", listOf(AccountPlaylist(shared.copy(title = "Canceled"), true))) {
                        if (++checks == 2) throw kotlinx.coroutines.CancellationException("Probe cancellation")
                    }
                }
                check(canceled.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                check(dao.getPlaylist("shared")?.title == "Updated")
                check(dao.getAccountPlaylists("account-a").first().none { it.isLiked })
                dao.replaceAccountPlaylists("account-a", emptyList()) {}
                check(dao.getAccountPlaylists("account-a").first().map { it.playlist.id } == listOf("local"))
                check(dao.getAccountPlaylists("account-b").first() == otherAccount)
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun verifyDataStore(context: Context) = runBlocking {
        val job = SupervisorJob()
        val file = context.preferencesDataStoreFile(NAME)
        val preferences = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file },
        )
        try {
            val key = stringPreferencesKey("marker")
            preferences.edit { it[key] = "module" }
            check(preferences.data.first()[key] == "module")
            check(file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }
}
