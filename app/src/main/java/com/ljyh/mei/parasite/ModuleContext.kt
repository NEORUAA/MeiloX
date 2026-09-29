package com.ljyh.mei.parasite

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.view.Display
import android.view.LayoutInflater
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Keeps Android component identity in the host while isolating module resources and storage. */
internal class ModuleContext private constructor(
    base: Context,
    private val resourceContext: Context,
    private val moduleApplication: Context? = null,
) : ContextWrapper(base) {
    private val moduleTheme by lazy {
        resources.newTheme().apply { applyStyle(android.R.style.Theme_Material_Light_NoActionBar, true) }
    }

    override fun getResources(): Resources = resourceContext.resources
    override fun getAssets(): AssetManager = resources.assets
    override fun getClassLoader(): ClassLoader = ModuleContext::class.java.classLoader!!
    override fun getApplicationContext(): Context = moduleApplication ?: this
    override fun getTheme(): Resources.Theme = moduleTheme
    override fun setTheme(resid: Int) = moduleTheme.applyStyle(resid, true)
    override fun getSystemService(name: String): Any? = if (name == LAYOUT_INFLATER_SERVICE) {
        LayoutInflater.from(baseContext).cloneInContext(this)
    } else super.getSystemService(name)

    override fun startService(service: Intent): ComponentName? = super.startService(HostAppComponentHooks.route(service))
    override fun startForegroundService(service: Intent): ComponentName? = super.startForegroundService(HostAppComponentHooks.route(service))
    override fun stopService(name: Intent): Boolean = super.stopService(HostAppComponentHooks.route(name))
    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean =
        super.bindService(HostAppComponentHooks.route(service), connection, flags)

    override fun getDataDir(): File = directory(File(super.getDataDir(), ModuleStorage.NAMESPACE))
    override fun getFilesDir(): File = directory(File(dataDir, "files"))
    override fun getCacheDir(): File = scoped(super.getCacheDir())
    override fun getCodeCacheDir(): File = scoped(super.getCodeCacheDir())
    override fun getNoBackupFilesDir(): File = scoped(super.getNoBackupFilesDir())
    override fun getExternalFilesDir(type: String?): File? = super.getExternalFilesDir(null)?.let { externalFiles(it, type) }
    override fun getExternalFilesDirs(type: String?): Array<File?> =
        super.getExternalFilesDirs(null).map { it?.let { root -> externalFiles(root, type) } }.toTypedArray()
    override fun getExternalCacheDir(): File? = super.getExternalCacheDir()?.let(::scoped)
    override fun getExternalCacheDirs(): Array<File?> = super.getExternalCacheDirs().map { it?.let(::scoped) }.toTypedArray()
    override fun getExternalMediaDirs(): Array<File?> = super.getExternalMediaDirs().map { it?.let(::scoped) }.toTypedArray()
    override fun getObbDir(): File = scoped(super.getObbDir())
    override fun getObbDirs(): Array<File?> = super.getObbDirs().map { it?.let(::scoped) }.toTypedArray()

    override fun getDir(name: String, mode: Int): File {
        require(mode == MODE_PRIVATE)
        return directory(File(dataDir, "app_${ModuleStorage.name(name)}"))
    }
    override fun getFileStreamPath(name: String): File = File(filesDir, ModuleStorage.name(name))
    override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))
    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        require(mode == MODE_PRIVATE || mode == MODE_APPEND)
        return FileOutputStream(getFileStreamPath(name), mode == MODE_APPEND)
    }
    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()
    override fun fileList(): Array<String> = filesDir.list() ?: emptyArray()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(preferenceName(name), mode)
    override fun deleteSharedPreferences(name: String): Boolean = super.deleteSharedPreferences(preferenceName(name))
    override fun moveSharedPreferencesFrom(sourceContext: Context, name: String): Boolean = false

    override fun getDatabasePath(name: String): File = ModuleStorage.database(name, dataDir, noBackupFilesDir).also {
        directory(requireNotNull(it.parentFile))
    }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        super.openOrCreateDatabase(getDatabasePath(name).path, mode, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        super.openOrCreateDatabase(getDatabasePath(name).path, mode, factory, errorHandler)
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun databaseList(): Array<String> = File(dataDir, "databases").list() ?: emptyArray()
    override fun moveDatabaseFrom(sourceContext: Context, name: String): Boolean = false

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context = ModuleContext(
        baseContext.createConfigurationContext(overrideConfiguration),
        resourceContext.createConfigurationContext(overrideConfiguration), applicationContext,
    )
    override fun createDisplayContext(display: Display): Context = ModuleContext(
        baseContext.createDisplayContext(display), resourceContext.createDisplayContext(display), applicationContext,
    )
    override fun createDeviceProtectedStorageContext(): Context = ModuleContext(
        baseContext.createDeviceProtectedStorageContext(), resourceContext,
    )

    fun wrap(base: Context): Context = ModuleContext(base, resourceContext, applicationContext)

    private fun preferenceName(name: String) = "${ModuleStorage.NAMESPACE}_${ModuleStorage.name(name)}"
    private fun scoped(file: File): File = directory(File(file, ModuleStorage.NAMESPACE))
    private fun externalFiles(root: File, type: String?): File = scoped(root).let {
        if (type == null) it else directory(File(it, ModuleStorage.name(type)))
    }
    private fun directory(file: File): File = file.apply {
        check(isDirectory || mkdirs() || isDirectory) { "Cannot create module storage" }
    }

    companion object {
        fun create(host: Context, modulePackage: String): ModuleContext =
            ModuleContext(host, host.createPackageContext(modulePackage, 0))
    }
}
