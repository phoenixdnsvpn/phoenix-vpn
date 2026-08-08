package net.vaydns.phoenix

object CryptoHelper {

    fun encrypt(value: String): String {
        if (value.isBlank()) return value
        return try {
            mobile.Mobile.encryptText(value) ?: value
        } catch (e: Exception) {
            e.printStackTrace()
            value
        }
    }

    fun decrypt(value: String): String {
        if (value.isBlank()) return value
        return try {
            mobile.Mobile.decryptText(value) ?: value
        } catch (e: Exception) {
            e.printStackTrace()
            value
        }
    }
}