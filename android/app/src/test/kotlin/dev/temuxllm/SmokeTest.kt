package dev.temuxllm

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Smoke test: verify the JUnit 4 + Kotlin test source set is wired up.
 * If `./gradlew :app:testDebugUnitTest` runs this and reports green,
 * the test framework dependencies and source layout are correct.
 */
class SmokeTest {
    @Test fun `arithmetic still works`() {
        assertEquals(4, 2 + 2)
    }

    @Test fun `top-level GenerateEvent types are accessible without LiteRT-LM`() {
        // Verifies the interface extraction: GenerateEvent moved out of
        // the concrete LlmEngine class so test fakes don't drag in JNI.
        val token: GenerateEvent = GenerateEvent.Token("hi")
        assertTrue(token is GenerateEvent.Token)
        assertEquals("hi", (token as GenerateEvent.Token).text)
    }
}
