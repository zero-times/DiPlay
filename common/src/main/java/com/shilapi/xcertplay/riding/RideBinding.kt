package com.shilapi.xcertplay.riding

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Both pairing proof and long-lived credential stay in no-backup storage, encrypted by Android Keystore. */
class RideBinding(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir,"riding-binding.enc"))
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val raw=file.readFully(); require(raw.size>28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,raw.copyOfRange(0,12)))
        return JSONObject(String(cipher.doFinal(raw.copyOfRange(12,raw.size)),Charsets.UTF_8))
    }
    @Synchronized fun save(value: JSONObject) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val raw=cipher.iv+cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        val stream=file.startWrite()
        try { stream.write(raw);file.finishWrite(stream) } catch(error:Exception) { file.failWrite(stream);throw error }
    }
    @Synchronized fun installation(): JSONObject {
        val value=read()
        if(!value.has("installation_id")) {
            value.put("installation_id",UUID.randomUUID().toString()).put("registration_secret", Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)).put("binding_state","UNBOUND")
            save(value)
        }
        return value
    }
    companion object {
        private const val ALIAS="diplay.riding.binding.v1"
        @Volatile private var instance:RideBinding?=null
        fun get(context:Context):RideBinding = instance ?: synchronized(this) {instance ?: RideBinding(context.applicationContext).also {instance=it}}
    }
}
