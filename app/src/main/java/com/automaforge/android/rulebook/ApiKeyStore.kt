package com.automaforge.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** User-supplied secret encrypted with a non-exportable Android Keystore key. Never embedded in the APK. */
class ApiKeyStore(context: Context) {
    private val prefs=context.getSharedPreferences("private_settings",0)
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("automaforge-openai",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("automaforge-openai",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(value: String) {
        if(value.isBlank()) { clear();return }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encoded=Base64.encodeToString(cipher.doFinal(value.trim().toByteArray()),Base64.NO_WRAP)
        check(prefs.edit().putString("api_key_encrypted",encoded).putString("api_key_iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).remove("api_key").commit())
    }
    @Synchronized fun read(): String {
        synchronized(ApiKeyStore::class.java) {
            val legacy=prefs.getString("api_key","").orEmpty()
            if(legacy.isNotEmpty())save(legacy)
            val encoded=prefs.getString("api_key_encrypted",null) ?: return ""
            val iv=Base64.decode(prefs.getString("api_key_iv","").orEmpty(),Base64.NO_WRAP)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv))
                String(doFinal(Base64.decode(encoded,Base64.NO_WRAP)),Charsets.UTF_8)
            }
        }
    }
    fun clear() { prefs.edit().remove("api_key").remove("api_key_encrypted").remove("api_key_iv").commit() }
}
