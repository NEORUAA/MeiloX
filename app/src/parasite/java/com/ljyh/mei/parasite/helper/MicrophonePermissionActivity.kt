package com.ljyh.mei.parasite.helper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/** No music UI or backend graph is initialized in this module-owned activity. */
class MicrophonePermissionActivity : Activity() {
    private var hostUid = -1
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (runCatching { hostUid = HelperCaller.requireActivity(this) }.isFailure) {
            finish()
            return
        }
        token = intent.getStringExtra(MicrophoneProtocol.TOKEN)?.takeIf {
            runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false)
        }
        if (token == null) { finish(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) arm()
        else if (savedInstanceState == null) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) arm()
        else finish()
    }

    private fun arm() {
        val grant = token ?: return
        runCatching {
            HelperCaller.requireActivity(this)
            MicrophoneGrants.instance.authorize(grant, hostUid)
            // Start while the module activity is visible: the TV UID has no microphone permission.
            startForegroundService(Intent(this, MicrophoneCaptureService::class.java)
                .putExtra(MicrophoneProtocol.TOKEN, grant))
            setResult(RESULT_OK, Intent().putExtra(MicrophoneProtocol.TOKEN, grant))
        }
        finish()
    }
}
