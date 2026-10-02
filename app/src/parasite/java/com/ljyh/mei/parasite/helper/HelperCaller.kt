package com.ljyh.mei.parasite.helper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import com.ljyh.mei.parasite.HostIdentity
import java.security.MessageDigest

internal object HelperCaller {
    fun requireHost(context: Context, uid: Int = Binder.getCallingUid()): Int {
        val manager = context.packageManager
        require(manager.getPackagesForUid(uid)?.toList() == listOf(HostIdentity.PACKAGE)) { "Untrusted helper caller" }
        val info = manager.getPackageInfo(HostIdentity.PACKAGE,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        require(HostIdentity.accepts(info.packageName, info.longVersionCode, info.versionName, signers)) {
            "Unsupported helper host"
        }
        return uid
    }

    fun requireActivity(context: android.app.Activity): Int {
        require(context.callingPackage == HostIdentity.PACKAGE) { "Helper requires a verified activity result caller" }
        return requireHost(context, context.packageManager.getPackageUid(HostIdentity.PACKAGE, 0))
    }
}
