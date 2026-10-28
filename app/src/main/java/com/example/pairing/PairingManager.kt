package com.example.pairing

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID

sealed class PairingState {
    data class Unpaired(val deviceId: String, val pairingCode: String) : PairingState()
    data class Paired(val deviceId: String, val deviceToken: String) : PairingState()
}

class PairingManager(private val context: Context) {

    private val securePrefs: SharedPreferences = createSecurePreferences()

    private val _pairingState = MutableStateFlow<PairingState>(determineInitialState())
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private fun createSecurePreferences(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "secure_signage_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Hardware Keystore / EncryptedSharedPreferences fallback: ${e.message}")
            context.getSharedPreferences("signage_credentials_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun determineInitialState(): PairingState {
        val deviceId = getOrCreateDeviceId()
        val token = securePrefs.getString(KEY_DEVICE_TOKEN, null)
        return if (!token.isNullOrBlank()) {
            PairingState.Paired(deviceId, token)
        } else {
            val code = getOrCreatePairingCode()
            PairingState.Unpaired(deviceId, code)
        }
    }

    fun getDeviceId(): String = getOrCreateDeviceId()

    fun getPairingCode(): String = getOrCreatePairingCode()

    fun getDeviceToken(): String? = securePrefs.getString(KEY_DEVICE_TOKEN, null)

    fun isPaired(): Boolean = !getDeviceToken().isNullOrBlank()

    private fun getOrCreateDeviceId(): String {
        var id = securePrefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            val hardwareId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) { null }

            id = if (!hardwareId.isNullOrBlank() && hardwareId != "9774d56d682e549c") {
                "TV-${hardwareId.takeLast(8).uppercase()}"
            } else {
                "TV-${UUID.randomUUID().toString().take(8).uppercase()}"
            }
            securePrefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    private fun getOrCreatePairingCode(): String {
        var code = securePrefs.getString(KEY_PAIRING_CODE, null)
        if (code.isNullOrBlank()) {
            val random = SecureRandom()
            val number = random.nextInt(900_000) + 100_000
            code = number.toString()
            securePrefs.edit().putString(KEY_PAIRING_CODE, code).apply()
        }
        return code
    }

    /**
     * Confirms pairing when backend confirms device is paired to organization.
     */
    fun confirmPairing(deviceToken: String) {
        securePrefs.edit()
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .remove(KEY_PAIRING_CODE)
            .apply()

        _pairingState.value = PairingState.Paired(getOrCreateDeviceId(), deviceToken)
        Log.i(TAG, "Device successfully paired! Token stored.")
    }

    /**
     * Bypass pairing for standalone/demo testing in kiosk mode.
     */
    fun skipPairingForStandaloneDemo() {
        confirmPairing("standalone-demo-token-${System.currentTimeMillis()}")
    }

    fun unpair() {
        securePrefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_PAIRING_CODE)
            .apply()

        val deviceId = getOrCreateDeviceId()
        val newCode = getOrCreatePairingCode()
        _pairingState.value = PairingState.Unpaired(deviceId, newCode)
        Log.i(TAG, "Device unpaired. New pairing code generated: $newCode")
    }

    companion object {
        private const val TAG = "PairingManager"
        private const val KEY_DEVICE_ID = "pref_device_id"
        private const val KEY_PAIRING_CODE = "pref_pairing_code"
        private const val KEY_DEVICE_TOKEN = "pref_device_token"
    }
}
