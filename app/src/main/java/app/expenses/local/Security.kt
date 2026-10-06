package app.expenses.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    val random = SecureRandom()

    fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size >= 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, blob.copyOfRange(0, 12)),
        )
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }

    fun passwordWrap(password: String, key: ByteArray): ByteArray {
        val salt = bytes(16)
        val derived = derive(password, salt)
        return try {
            salt + encrypt(derived, key)
        } finally {
            derived.fill(0)
        }
    }

    fun passwordUnwrap(password: String, blob: ByteArray): ByteArray {
        require(blob.size >= 44)
        val derived = derive(password, blob.copyOfRange(0, 16))
        return try {
            decrypt(derived, blob.copyOfRange(16, blob.size))
        } finally {
            derived.fill(0)
        }
    }
}

class Security(context: Context) {
    private val prefs = context.getSharedPreferences("security", Context.MODE_PRIVATE)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val configured
        get() = prefs.contains("pin")

    val biometricEnabled
        get() = prefs.contains("bio")

    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun key(alias: String, biometric: Boolean = false): SecretKey {
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        val builder =
            KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
        if (biometric)
            builder.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(builder.build()) }
            .generateKey()
    }

    private fun deviceWrap(bytes: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key("expenses.device"))
        return encode(c.iv + c.doFinal(bytes))
    }

    private fun deviceUnwrap(text: String): ByteArray {
        val b = decode(text)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            key("expenses.device"),
            GCMParameterSpec(128, b.copyOfRange(0, 12)),
        )
        return c.doFinal(b.copyOfRange(12, b.size))
    }

    fun validatePassword(password: String) {
        require(
            (password.matches(Regex("[0-9]{6,64}"))) ||
                (password.length in 10..128 && !password.all(Char::isDigit))
        ) {
            "Use at least 6 digits for a PIN or 10 characters for a password (maximum 128)."
        }
    }

    fun setup(password: String): Pair<ByteArray, String> {
        check(!configured)
        validatePassword(password)
        val master = Crypto.bytes(32)
        val recovery =
            Crypto.bytes(24).joinToString("") { "%02X".format(it) }.chunked(6).joinToString("-")
        val pin = deviceWrap(Crypto.passwordWrap(password, master))
        val rec = deviceWrap(Crypto.passwordWrap(normalize(recovery), master))
        check(
            prefs
                .edit()
                .putString("pin", pin)
                .putString("recovery", rec)
                .putString(
                    "pendingRecovery",
                    encode(Crypto.encrypt(master, recovery.toByteArray())),
                )
                .commit()
        ) {
            "Could not save security settings."
        }
        return master to recovery
    }

    private fun normalize(text: String) =
        text.filter { !it.isWhitespace() && it != '-' }.uppercase()

    fun pendingRecovery(master: ByteArray): String? =
        prefs.getString("pendingRecovery", null)?.let {
            Crypto.decrypt(master, decode(it)).toString(Charsets.UTF_8)
        }

    fun acknowledgeRecovery() {
        prefs.edit().remove("pendingRecovery").commit()
    }

    private fun attempt(block: () -> ByteArray): ByteArray {
        val wait = prefs.getLong("wait", 0) - System.currentTimeMillis()
        require(wait <= 0) { "Too many attempts. Try again in ${(wait+999)/1000} seconds." }
        return try {
            block().also { prefs.edit().remove("attempts").remove("wait").commit() }
        } catch (e: Exception) {
            val n = (prefs.getInt("attempts", 0) + 1).coerceAtMost(20)
            prefs
                .edit()
                .putInt("attempts", n)
                .putLong(
                    "wait",
                    System.currentTimeMillis() +
                        if (n < 5) 0 else (30_000L shl (n - 5).coerceAtMost(5)),
                )
                .commit()
            throw IllegalArgumentException(
                "Authentication failed. Check your credentials or use your recovery code."
            )
        }
    }

    fun unlock(password: String) = attempt {
        Crypto.passwordUnwrap(password, deviceUnwrap(requireNotNull(prefs.getString("pin", null))))
    }

    fun recover(code: String, newPassword: String): ByteArray {
        validatePassword(newPassword)
        return attempt {
                Crypto.passwordUnwrap(
                    normalize(code),
                    deviceUnwrap(requireNotNull(prefs.getString("recovery", null))),
                )
            }
            .also { changePassword(it, newPassword) }
    }

    fun changePassword(master: ByteArray, password: String) {
        validatePassword(password)
        check(
            prefs
                .edit()
                .putString("pin", deviceWrap(Crypto.passwordWrap(password, master)))
                .remove("bio")
                .commit()
        ) {
            "Could not save password."
        }
        if (store.containsAlias("expenses.bio")) store.deleteEntry("expenses.bio")
    }

    fun disableBiometric() {
        prefs.edit().remove("bio").commit()
        if (store.containsAlias("expenses.bio")) store.deleteEntry("expenses.bio")
    }

    fun biometricCipher(enroll: Boolean): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (enroll) c.init(Cipher.ENCRYPT_MODE, key("expenses.bio", true))
        else {
            val blob = decode(requireNotNull(prefs.getString("bio", null)))
            c.init(
                Cipher.DECRYPT_MODE,
                key("expenses.bio", true),
                GCMParameterSpec(128, blob.copyOfRange(0, 12)),
            )
        }
        return c
    }

    fun enrollBiometric(cipher: Cipher, master: ByteArray) {
        check(prefs.edit().putString("bio", encode(cipher.iv + cipher.doFinal(master))).commit())
    }

    fun unlockBiometric(cipher: Cipher): ByteArray {
        val b = decode(requireNotNull(prefs.getString("bio", null)))
        return cipher.doFinal(b.copyOfRange(12, b.size))
    }
}
