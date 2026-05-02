package dev.temuxllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pure-Kotlin tests for AutoFallback. Cover the bits that don't need
 * Android Context: tier ladder, packed-summary round-trip.
 */
class AutoFallbackTest {

    @Test fun `nextLowerTier walks down the ladder`() {
        // For exact rungs, return the rung below.
        assertEquals(24576, AutoFallback.nextLowerTier(32768))
        assertEquals(16384, AutoFallback.nextLowerTier(24576))
        assertEquals(8192, AutoFallback.nextLowerTier(16384))
        assertEquals(4096, AutoFallback.nextLowerTier(8192))
        // Floor: at the lowest rung, return it unchanged.
        assertEquals(4096, AutoFallback.nextLowerTier(4096))
    }

    @Test fun `nextLowerTier of an off-rung value lands on the rung just below`() {
        // 20000 falls between 16384 and 24576. The "value that got us
        // killed" was 20000; the next safer value is 16384 (one step
        // down), not 8192 (two steps). codex outside-review v0.4.0.
        assertEquals(16384, AutoFallback.nextLowerTier(20000))
    }

    @Test fun `nextLowerTier of an on-rung value lands on the next rung below`() {
        // For exact rung values, "next lower" means the rung BELOW.
        assertEquals(8192, AutoFallback.nextLowerTier(16384))
        assertEquals(16384, AutoFallback.nextLowerTier(24576))
    }

    @Test fun `packStateSummary round-trip preserves max_tokens and backend`() {
        val packed = AutoFallback.packStateSummary(maxNumTokens = 16384, backend = "gpu", modelName = "model")
        assertEquals(14, packed.size) // version + int32 + byte + long
        val unpacked = AutoFallback.unpackStateSummary(packed)
        assertNotNull(unpacked)
        val (max, backend, _) = unpacked!!
        assertEquals(16384, max)
        assertEquals("gpu", backend)
    }

    @Test fun `unpackStateSummary returns null on too-short bytes`() {
        assertNull(AutoFallback.unpackStateSummary(ByteArray(5)))
        assertNull(AutoFallback.unpackStateSummary(null))
    }

    @Test fun `unpackStateSummary returns null on version byte mismatch`() {
        val bad = ByteArray(14) { 0 }
        bad[0] = 99 // wrong version
        assertNull(AutoFallback.unpackStateSummary(bad))
    }

    @Test fun `packStateSummary backend other than gpu encodes as cpu`() {
        val packed = AutoFallback.packStateSummary(8192, "cpu", "x")
        val (_, b, _) = AutoFallback.unpackStateSummary(packed)!!
        assertEquals("cpu", b)
        // Sanity: an arbitrary string also round-trips to "cpu"
        val packed2 = AutoFallback.packStateSummary(8192, "wat", "x")
        val (_, b2, _) = AutoFallback.unpackStateSummary(packed2)!!
        assertEquals("cpu", b2)
    }
}
