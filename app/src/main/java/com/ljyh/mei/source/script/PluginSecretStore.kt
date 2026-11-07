package com.ljyh.mei.source.script

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Config and plugin-owned storage are encrypted in independent instance namespaces. */
internal class PluginSecretStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "source-secrets").apply { mkdirs() }
    private val gson = Gson()
    private val lock get() = globalLock

    fun read(id: String, kind: String): Map<String, String> = synchronized(lock) {
        val file = File(directory, "$id-$kind.bin")
        if (!file.exists()) return@synchronized emptyMap()
        val data = file.readBytes()
        require(data.size in 29..MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(id), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD("$id:$kind:v1".toByteArray())
        val plaintext = cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
        gson.fromJson<Map<String, String>>(plaintext, object : TypeToken<Map<String, String>>() {}.type).orEmpty()
    }

    fun write(id: String, kind: String, values: Map<String, String>) = synchronized(lock) {
        val bytes = gson.toJson(values).toByteArray(Charsets.UTF_8)
        require(bytes.size < MAX_BYTES - 64)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(id))
        cipher.updateAAD("$id:$kind:v1".toByteArray())
        val encrypted = cipher.iv + cipher.doFinal(bytes)
        val temporary = File(directory, "$id-$kind.tmp")
        temporary.writeBytes(encrypted)
        val target = File(directory, "$id-$kind.bin")
        check(temporary.renameTo(target))
    }

    fun delete(id: String) = synchronized(lock) {
        listOf("config", "storage").forEach { File(directory, "$id-$it.bin").delete() }
        val artworkName = Regex("${Regex.escape(id)}-artwork-[a-f0-9]{64}\\.(bin|tmp)")
        directory.listFiles().orEmpty().filter { artworkName.matches(it.name) }.forEach { it.delete() }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("mei.source.$id")
    }

    internal fun pruneArtwork() = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.name.matches(Regex(".+-artwork-[a-f0-9]{64}\\.tmp")) }.forEach { it.delete() }
        val records = directory.listFiles().orEmpty().filter { it.name.matches(Regex(".+-artwork-[a-f0-9]{64}\\.bin")) }
            .sortedBy { it.lastModified() }
        var bytes = records.sumOf { it.length() }
        var count = records.size
        records.forEach { file ->
            if (bytes > 28L * 1024 * 1024 || count > 8128) {
                val size = file.length()
                if (file.delete()) { bytes -= size; count-- }
            }
        }
    }

    internal fun hasArtwork(id: String, hash: String): Boolean = synchronized(lock) {
        File(directory, "$id-artwork-$hash.bin").isFile
    }

    private fun key(id: String): SecretKey {
        val alias = "mei.source.$id"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object {
        const val MAX_BYTES = 128 * 1024
        val globalLock = Any()
    }
}
