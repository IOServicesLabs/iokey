package com.localboard.keyboard.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Transparent trampoline: the keyboard (a service) can't request runtime
 * permissions itself, so tapping the mic without a grant launches this to
 * show the system dialog, then it finishes immediately.
 *
 * A plain Activity because the manifest gives it a platform translucent theme.
 * AppCompatActivity throws on any theme outside Theme.AppCompat, and this runs
 * in the keyboard's process, so that would take the keyboard down with it.
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    private companion object {
        private const val REQ_MIC = 4101
    }
}
