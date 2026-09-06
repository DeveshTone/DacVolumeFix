package com.wrick.DacVolumeFix.data

import android.content.Context
import android.content.SharedPreferences

class DacRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "dac_volume_fix_prefs"
        private const val KEY_AUTO_APPLY = "auto_apply_enabled"
        private const val KEY_AUDIO_PERMISSION_PROMPTED = "audio_permission_prompted"
        private const val KEY_VOL_PREFIX = "vol_"
        private const val KEY_UNLOCKED_PREFIX = "unlocked_"
    }

    var isAutoApplyEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPLY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_APPLY, value).apply()

    var hasPromptedAudioPermission: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_PERMISSION_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_PERMISSION_PROMPTED, value).apply()

    fun isDeviceUnlocked(vendorId: Int, productId: Int): Boolean {
        return prefs.getBoolean("${KEY_UNLOCKED_PREFIX}${vendorId}_${productId}", false)
    }

    fun setDeviceUnlocked(vendorId: Int, productId: Int, unlocked: Boolean) {
        prefs.edit().putBoolean("${KEY_UNLOCKED_PREFIX}${vendorId}_${productId}", unlocked).apply()
    }

    fun clearAllUnlockedStates() {
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(KEY_UNLOCKED_PREFIX)) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun getTargetVolume(vendorId: Int, productId: Int): Int {
        return prefs.getInt(volumeKey(vendorId, productId), 0).coerceIn(-80, 0)
    }

    fun setTargetVolume(vendorId: Int, productId: Int, volumeDb: Int) {
        prefs.edit().putInt(volumeKey(vendorId, productId), volumeDb.coerceIn(-80, 0)).apply()
    }

    private fun volumeKey(vendorId: Int, productId: Int): String {
        return "${KEY_VOL_PREFIX}${vendorId}_${productId}"
    }
}
