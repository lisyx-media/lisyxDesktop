package com.nuvio.app.features.lisy

import com.nuvio.app.features.plugins.cryptointerop.CCKeyDerivationPBKDF
import com.nuvio.app.features.plugins.cryptointerop.kCCPBKDF2
import com.nuvio.app.features.plugins.cryptointerop.kCCPRFHmacAlgSHA256
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret

internal actual object TvParentalCrypto {
    @OptIn(ExperimentalForeignApi::class)
    actual fun derive(pin: String, salt: ByteArray): ByteArray {
        val password = pin.encodeToByteArray()
        val output = ByteArray(32)
        try {
            val status = CCKeyDerivationPBKDF(
                kCCPBKDF2, password.refTo(0), password.size.toULong(),
                salt.refTo(0).reinterpret(), salt.size.toULong(), kCCPRFHmacAlgSHA256,
                TV_PARENTAL_PIN_ITERATIONS.toUInt(), output.refTo(0).reinterpret(), output.size.toULong(),
            )
            check(status == 0) { "PIN derivation failed" }
            return output
        } finally {
            password.fill(0)
        }
    }
}
