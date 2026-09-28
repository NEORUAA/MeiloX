package com.ljyh.mei.parasite

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.ljyh.mei.di.AppDatabase
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
            report("runtime_storage room_schema=true")
            verifyDataStore(context)
            report("runtime_storage datastore=true")
        } catch (error: Throwable) {
            report("runtime_storage_failed type=${error.javaClass.name}")
        }
    }

    private fun verifyRoom(context: Context) {
        val name = "${NAME}_room"
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE type='table'").use {
                check(it.moveToFirst() && it.getInt(0) > 1)
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
