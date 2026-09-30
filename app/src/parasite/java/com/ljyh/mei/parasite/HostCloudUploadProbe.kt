package com.ljyh.mei.parasite

import android.content.Context
import com.ljyh.mei.data.repository.CloudUploadAuthorization
import com.ljyh.mei.data.repository.CloudUploadFile
import java.io.File
import java.security.MessageDigest

/** Real SDK pre-transfer rejection only. No authorization calls, valid tokens or user bytes. */
internal object HostCloudUploadProbe {
    fun run(context: Context) {
        val file = File.createTempFile("cloud-sdk-fixture-", ".bin", context.cacheDir)
        try {
            val backend = TvHostNosUploadBackend(HostRuntimeProbe.applicationContext.baseContext.classLoader)
            val authorization = CloudUploadAuthorization("fixture", "fixture/file", "invalid-fixture-token")
            fun input() = CloudUploadFile(file, "Fixture.wav", "wav", "Fixture", file.length(),
                MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) },
                "Fixture", "Test", "Test", "audio/wav")
            var progress = 0
            check(backend.upload(input(), authorization, { false }) { _, _ -> progress++ } == -1)
            file.writeBytes(byteArrayOf(1, 2, 3))
            var checks = 0
            // The first check is the adapter; every SDK digest/cancel callback then cancels.
            check(backend.upload(input(), authorization, { ++checks > 1 }) { _, _ -> progress++ } == -2)
            check(checks > 1 && progress == 0)
            HostRuntimeProbe.report("cloud_upload_sdk_closed_passed initialized=true empty_rejected=true " +
                "sdk_cancel_callback=true canceled_before_lbs=true progress_callbacks=0 synthetic_only=true " +
                "authorization_calls=0 transfers=0 publication_calls=0")
        } finally { check(file.delete()) }
    }
}
