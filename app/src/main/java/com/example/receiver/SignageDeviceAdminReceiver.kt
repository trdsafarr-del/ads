package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class SignageDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled for SignageTV 24x7 Kiosk mode")
        Toast.makeText(context, "SignageTV Device Admin Enabled (24x7 Protection Active)", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin disabled for SignageTV")
        Toast.makeText(context, "SignageTV Device Admin Disabled", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "SignageDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, SignageDeviceAdminReceiver::class.java)
        }
    }
}
