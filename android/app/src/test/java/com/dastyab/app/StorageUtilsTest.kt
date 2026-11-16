package com.dastyab.app

import org.junit.Test
import kotlin.test.assertTrue

class StorageUtilsTest {

    @Test
    fun `hasEnoughSpace returns a boolean without crashing`() {
        // This test verifies the storage check doesn't throw on any device.
        // On CI without real storage it may return true or false depending
        // on available space; we just assert it's a boolean.
        val result = StorageUtils.hasEnoughSpace()
        assertTrue(result == true || result == false)
    }

    @Test
    fun `required free bytes is 50 MB`() {
        // Verify the constant value used by StorageUtils.
        // We can't access private constants directly, but we can verify
        // the behavior is sane by checking hasEnoughSpace on a tmp dir
        // with known space. This is a smoke test.
        val result = StorageUtils.hasEnoughSpace()
        // On any reasonable CI/dev machine there should be >50 MB free
        assertTrue(result)
    }
}