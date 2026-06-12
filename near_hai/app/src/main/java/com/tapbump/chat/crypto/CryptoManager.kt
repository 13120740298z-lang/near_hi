package com.tapbump.chat.crypto

import android.util.Base64
import com.tapbump.chat.util.PreferenceManager
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

class CryptoManager(private val preferenceManager: PreferenceManager) {

    companion object {
        private const val RSA_ALGORITHM = "RSA"
        private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        private const val KEY_SIZE = 2048
        private const val MAX_MESSAGE_LENGTH = 200 // RSA 2048 OAEP 最大加密长度限制
    }

    /**
     * 生成 RSA 2048 密钥对，存储到 SharedPreferences
     */
    fun generateKeyPairIfNeeded() {
        if (preferenceManager.getPublicKey() != null &&
            preferenceManager.getPrivateKey() != null
        ) return

        val generator = KeyPairGenerator.getInstance(RSA_ALGORITHM)
        generator.initialize(KEY_SIZE)
        val keyPair = generator.generateKeyPair()

        val publicKeyStr = Base64.encodeToString(
            keyPair.public.encoded,
            Base64.NO_WRAP
        )
        val privateKeyStr = Base64.encodeToString(
            keyPair.private.encoded,
            Base64.NO_WRAP
        )

        preferenceManager.setKeyPair(publicKeyStr, privateKeyStr)

        // 计算公钥哈希（前8字节的十六进制）
        val hash = computePublicKeyHash(keyPair.public)
        preferenceManager.setPublicKeyHash(hash)
    }

    /**
     * 计算公钥的 SHA-256 哈希，取前8字节的十六进制字符串
     */
    fun computePublicKeyHash(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey.encoded)
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 获取我的公钥哈希
     */
    fun getMyPublicKeyHash(): String {
        return preferenceManager.getPublicKeyHash() ?: ""
    }

    /**
     * 获取我的公钥字符串
     */
    fun getMyPublicKey(): String {
        return preferenceManager.getPublicKey() ?: ""
    }

    /**
     * 从 Base64 字符串加载公钥对象
     */
    fun loadPublicKey(base64Key: String): PublicKey {
        val keyBytes = Base64.decode(base64Key, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        val factory = KeyFactory.getInstance(RSA_ALGORITHM)
        return factory.generatePublic(spec)
    }

    /**
     * 加载我的私钥对象
     */
    fun loadMyPrivateKey(): PrivateKey {
        val privateKeyStr = preferenceManager.getPrivateKey()
            ?: throw IllegalStateException("私钥不存在")
        val keyBytes = Base64.decode(privateKeyStr, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val factory = KeyFactory.getInstance(RSA_ALGORITHM)
        return factory.generatePrivate(spec)
    }

    /**
     * 使用对方公钥加密消息
     * 限制：消息不超过200字符（RSA 2048 OAEP 限制）
     */
    fun encrypt(message: String, recipientPublicKeyStr: String): String {
        if (message.length > MAX_MESSAGE_LENGTH) {
            throw IllegalArgumentException("消息超过${MAX_MESSAGE_LENGTH}字符限制")
        }
        val publicKey = loadPublicKey(recipientPublicKeyStr)
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * 使用自己私钥解密消息
     */
    fun decrypt(encryptedBase64: String): String {
        val privateKey = loadMyPrivateKey()
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val encrypted = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val decrypted = cipher.doFinal(encrypted)
        return String(decrypted, Charsets.UTF_8)
    }
}
