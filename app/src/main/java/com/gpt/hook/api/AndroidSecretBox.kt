package com.gpt.hook.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android 侧加密存储实现：AndroidKeystore 主密钥 + AES256-GCM。
 *
 * 为什么不用普通 SharedPreferences：OAuth 的 access/refresh token 一旦明文落盘，
 * 等于把账号长期授权交给任何能读应用私有目录的人。这里用 Keystore 包裹，
 * 密钥不出 TEE/StrongBox，备份出来后也无法在别的设备解密。
 *
 * 失败兜底：设备没有可用 Keystore（极老的 ROM / 被裁剪的实现）时退回进程内存，
 * 宁可让用户下次重新登录，也不静默降级为明文存放。
 */
class AndroidSecretBox(context: Context) : ChatGptCredentialStore.SecretBox {

    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    private val memoryFallback = ChatGptCredentialStore.MemoryBox()

    private val encrypted: Boolean get() = prefs != null

    override fun read(key: String): String? =
        prefs?.getString(key, null) ?: if (prefs == null) memoryFallback.read(key) else null

    override fun write(key: String, value: String) {
        val p = prefs
        if (p == null) {
            memoryFallback.write(key, value)
            return
        }
        // 用 commit：换 token 后必须立刻可读，apply 的异步落盘在进程被杀时会丢凭证
        p.edit().putString(key, value).commit()
    }

    override fun erase(key: String) {
        val p = prefs
        if (p == null) {
            memoryFallback.erase(key)
            return
        }
        p.edit().remove(key).commit()
    }

    /** 给 UI/状态页展示用：当前是否真的在用硬件加密存储。 */
    fun isEncrypted(): Boolean = encrypted

    companion object {
        private const val FILE_NAME = "dsh_chatgpt_oauth_secure"
    }
}
