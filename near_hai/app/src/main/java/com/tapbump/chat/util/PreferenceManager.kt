package com.tapbump.chat.util

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "tapbump_prefs"

        const val KEY_DEVICE_ID = "device_id"
        const val KEY_NICKNAME = "nickname"
        const val KEY_AVATAR_INDEX = "avatar_index"
        const val KEY_PUBLIC_KEY = "public_key"
        const val KEY_PRIVATE_KEY = "private_key"
        const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
        const val KEY_PUBLIC_KEY_HASH = "public_key_hash"
    }

    // ========== 设备ID ==========

    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    // ========== 昵称 ==========

    fun getNickname(): String {
        val existing = prefs.getString(KEY_NICKNAME, null)
        if (existing != null) return existing
        val defaultName = "用户${(1000..9999).random()}"
        prefs.edit().putString(KEY_NICKNAME, defaultName).apply()
        return defaultName
    }

    fun setNickname(nickname: String) {
        prefs.edit().putString(KEY_NICKNAME, nickname).apply()
    }

    // ========== 头像索引 ==========

    fun getAvatarIndex(): Int {
        val index = prefs.getInt(KEY_AVATAR_INDEX, -1)
        if (index >= 0) return index
        val randomIndex = (0..7).random()
        prefs.edit().putInt(KEY_AVATAR_INDEX, randomIndex).apply()
        return randomIndex
    }

    fun setAvatarIndex(index: Int) {
        prefs.edit().putInt(KEY_AVATAR_INDEX, index).apply()
    }

    // ========== 密钥对 ==========

    fun getPublicKey(): String? = prefs.getString(KEY_PUBLIC_KEY, null)

    fun getPrivateKey(): String? = prefs.getString(KEY_PRIVATE_KEY, null)

    fun setKeyPair(publicKey: String, privateKey: String) {
        prefs.edit()
            .putString(KEY_PUBLIC_KEY, publicKey)
            .putString(KEY_PRIVATE_KEY, privateKey)
            .apply()
    }

    fun getPublicKeyHash(): String? = prefs.getString(KEY_PUBLIC_KEY_HASH, null)

    fun setPublicKeyHash(hash: String) {
        prefs.edit().putString(KEY_PUBLIC_KEY_HASH, hash).apply()
    }

    // ========== 首次启动 ==========

    fun isFirstLaunch(): Boolean = prefs.getBoolean(KEY_IS_FIRST_LAUNCH, true)

    fun setFirstLaunchDone() {
        prefs.edit().putBoolean(KEY_IS_FIRST_LAUNCH, false).apply()
    }
}
