package com.example.newapp.utils

import android.util.Base64

object EncryptionUtil {
    fun encodeToBase64(text: String): String {
        return Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
    }

    fun decodeFromBase64(base64: String): String {
        return String(Base64.decode(base64, Base64.NO_WRAP))
    }
} 