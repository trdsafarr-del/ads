package com.example.pairing

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.UUID

sealed class PairingState {
    data class Unpaired(
        val deviceId: String,
        val pairingCode: String
    ) : PairingState()

    data class Paired(
        val deviceId: String,
        val pairingCode: String,
        val deviceToken: String,
        val screenName: String,
        val pairedAtTimestamp: Long
    ) : PairingState()
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

    /**
     * Validates that a stored token is a genuine CMS server token (`tok_...`)
     * and NOT a legacy fake/auto/demo token.
     */
    private fun isGenuineServerToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        if (token.startsWith("auto_paired_token_")) return false
        if (token.startsWith("standalone-demo-token-")) return false
        if (token.startsWith("cms_token_")) return false
        return token.startsWith("tok_") && securePrefs.getBoolean(KEY_VERIFIED_BY_USER_CODE, false)
    }

    private fun determineInitialState(): PairingState {
        val deviceId = getOrCreateDeviceId()
        val code = getOrCreatePairingCode()
        val rawToken = securePrefs.getString(KEY_DEVICE_TOKEN, null)

        // Purge any fake or unverified tokens immediately so fake pairing never appears
        if (!isGenuineServerToken(rawToken)) {
            if (!rawToken.isNullOrBlank()) {
                securePrefs.edit()
                    .remove(KEY_DEVICE_TOKEN)
                    .remove(KEY_PAIRED_SCREEN_NAME)
                    .remove(KEY_PAIRED_TIMESTAMP)
                    .putBoolean(KEY_VERIFIED_BY_USER_CODE, false)
                    .apply()
            }
            return PairingState.Unpaired(deviceId = deviceId, pairingCode = code)
        }

        val token = rawToken!!
        val screenName = securePrefs.getString(KEY_PAIRED_SCREEN_NAME, null)
            ?: "SignageTV (${deviceId.takeLast(4)})"
        val pairedAt = securePrefs.getLong(KEY_PAIRED_TIMESTAMP, System.currentTimeMillis())

        return PairingState.Paired(
            deviceId = deviceId,
            pairingCode = code,
            deviceToken = token,
            screenName = screenName,
            pairedAtTimestamp = pairedAt
        )
    }

    fun getDeviceId(): String = getOrCreateDeviceId()

    fun getPairingCode(): String = getOrCreatePairingCode()

    fun getDeviceToken(): String? {
        val raw = securePrefs.getString(KEY_DEVICE_TOKEN, null)
        return if (isGenuineServerToken(raw)) raw else null
    }

    fun getPairedScreenName(): String {
        val deviceId = getOrCreateDeviceId()
        return securePrefs.getString(KEY_PAIRED_SCREEN_NAME, null)
            ?: "SignageTV (${deviceId.takeLast(4)})"
    }

    fun getPairedTimestamp(): Long {
        return securePrefs.getLong(KEY_PAIRED_TIMESTAMP, 0L)
    }

    fun isPaired(): Boolean = getDeviceToken() != null

    private fun getOrCreateDeviceId(): String {
        var id = securePrefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            val hardwareId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                null
            }

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
        if (code.isNullOrBlank() || code.length != 6 || !code.all { it.isDigit() }) {
            code = generateSixDigitCode()
            securePrefs.edit().putString(KEY_PAIRING_CODE, code).apply()
        }
        return code
    }

    private fun generateSixDigitCode(): String {
        val random = SecureRandom()
        val number = random.nextInt(900_000) + 100_000
        return number.toString()
    }

    /**
     * Generates a brand-new 6-digit pairing code when the device is unpaired.
     */
    fun regeneratePairingCode(): String {
        val deviceId = getOrCreateDeviceId()
        val newCode = generateSixDigitCode()
        securePrefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .putBoolean(KEY_VERIFIED_BY_USER_CODE, false)
            .putString(KEY_PAIRING_CODE, newCode)
            .apply()
        _pairingState.value = PairingState.Unpaired(deviceId = deviceId, pairingCode = newCode)
        Log.i(TAG, "Generated new 6-digit pairing code: $newCode")
        return newCode
    }

    /**
     * Confirms pairing ONLY when the backend confirms the user approved the 6-digit pairing code.
     * Preserves KEY_PAIRING_CODE so the Admin Panel can display the paired code alongside status.
     */
    fun confirmPairing(deviceToken: String, screenName: String? = null) {
        val deviceId = getOrCreateDeviceId()
        val code = getOrCreatePairingCode()
        val resolvedName = screenName?.takeIf { it.isNotBlank() }
            ?: "SignageTV (${deviceId.takeLast(4)})"
        val now = System.currentTimeMillis()

        securePrefs.edit()
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_PAIRED_SCREEN_NAME, resolvedName)
            .putLong(KEY_PAIRED_TIMESTAMP, now)
            .putBoolean(KEY_VERIFIED_BY_USER_CODE, true)
            .apply()

        _pairingState.value = PairingState.Paired(
            deviceId = deviceId,
            pairingCode = code,
            deviceToken = deviceToken,
            screenName = resolvedName,
            pairedAtTimestamp = now
        )
        Log.i(TAG, "Device genuinely paired with CMS! Code=$code, Name=$resolvedName")
    }

    /**
     * Unpairs the device, clears server token, and generates a fresh 6-digit pairing code.
     */
    fun unpair(): String {
        val deviceId = getOrCreateDeviceId()
        val newCode = generateSixDigitCode()
        securePrefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_PAIRED_SCREEN_NAME)
            .remove(KEY_PAIRED_TIMESTAMP)
            .putBoolean(KEY_VERIFIED_BY_USER_CODE, false)
            .putString(KEY_PAIRING_CODE, newCode)
            .apply()

        _pairingState.value = PairingState.Unpaired(deviceId = deviceId, pairingCode = newCode)
        Log.i(TAG, "Device unpaired. New 6-digit pairing code generated: $newCode")
        return newCode
    }

    companion object {
        private const val TAG = "PairingManager"
        private const val KEY_DEVICE_ID = "pref_device_id"
        private const val KEY_PAIRING_CODE = "pref_pairing_code"
        private const val KEY_DEVICE_TOKEN = "pref_device_token"
        private const val KEY_PAIRED_SCREEN_NAME = "pref_paired_screen_name"
        private const val KEY_PAIRED_TIMESTAMP = "pref_paired_timestamp"
        private const val KEY_VERIFIED_BY_USER_CODE = "pref_verified_by_user_code"
    }
}
