package com.nuvio.app.features.lisy

internal expect object TvParentalCrypto {
    fun derive(pin: String, salt: ByteArray): ByteArray
}
