package com.nuvio.app.features.lisy

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal actual object TvParentalCrypto {
    actual fun derive(pin: String, salt: ByteArray): ByteArray {
        val password = pin.toCharArray()
        val specification = PBEKeySpec(password, salt, TV_PARENTAL_PIN_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
            password.fill('\u0000')
        }
    }
}
