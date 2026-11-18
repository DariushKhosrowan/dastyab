package com.dastyab.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeEncoderTest {

    @Test
    fun encodesShortTextIntoVersion1Matrix() {
        val qr = QrCodeEncoder.encode("dastyab.dev")
        assertNotNull(qr)
        assertEquals(1, qr!!.version)
        assertEquals(21, qr.size)
        assertEquals(21 * 21, qr.modules.size)
        // Top-left finder pattern center (3,3) and outer corner (0,0) must be dark
        assertTrue(qr.get(0, 0))
        assertTrue(qr.get(3, 3))
        // Top-right finder pattern corner must be dark
        assertTrue(qr.get(qr.size - 1, 0))
        // Bottom-left finder pattern corner must be dark
        assertTrue(qr.get(0, qr.size - 1))
    }

    @Test
    fun encodesRealisticVlessUriIntoHigherVersionMatrix() {
        val vless = "vless://11111111-2222-3333-4444-555555555555@104.16.12.34:443?encryption=none&security=tls&type=ws&host=my-worker.pages.dev&sni=my-worker.pages.dev&path=%2Fws#DastYab-Clean"
        val qr = QrCodeEncoder.encode(vless)
        assertNotNull(qr)
        assertTrue(qr!!.version in 7..15)
        assertEquals(qr.version * 4 + 17, qr.size)
        // Dark module at (8, size - 8) per ISO/IEC 18004
        assertTrue(qr.get(8, qr.size - 8))
    }

    @Test
    fun rejectsEmptyOrOversizedPayload() {
        assertNull(QrCodeEncoder.encode(""))
        assertNull(QrCodeEncoder.encode("a".repeat(600)))
    }
}
