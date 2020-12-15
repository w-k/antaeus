package io.pleo.antaeus.core.services

import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.*
import java.lang.Exception

class RetryTest {
    private val mockApi = mockk<() -> Unit>()
    private val mockSleep = mockk<(Long) -> Unit>(relaxed = true)

    @BeforeEach
    fun beforEach() {
        every { mockApi() } throws Exception("Test exception")
    }

    @Nested
    inner class WithNonRetriableError {

        @Test
        fun `doesn't retry even if max count is greater than zero`() {
            val withRetry = createRetry(10, sleep = mockSleep) {
                false
            }
            try {
                withRetry {
                    mockApi()
                }
            } catch (t: Throwable) {
                // nothing to do
            } finally {
                verify(exactly = 1) { mockApi() }
            }
        }

        @Test
        fun `re-throws error when max count is greater than zero and subsequent request would succeed`() {
            val withRetry = createRetry(10, sleep = mockSleep) {
                false
            }
            every { mockApi() } answers { throw Exception("Test exception") } andThen {}
            assertThrows<Exception> {
                withRetry {
                    mockApi()
                }
            }
        }
    }

    @Nested
    inner class WithRetriableError {

        @Nested
        inner class WithZeroMaxRetryCount {
            val withRetry = createRetry(0, sleep = mockSleep) {
                true
            }

            @Test
            fun `doesn't retry`() {
                try {
                    withRetry {
                        mockApi()
                    }
                } catch (t: Throwable) {
                    // nothing to do
                } finally {
                    verify(exactly = 1) { mockApi() }
                }
            }

            @Test
            fun `re-throws error`() {
                every { mockApi() } throws Exception("Test exception")
                assertThrows<Exception> {
                    withRetry {
                        mockApi()
                    }
                }
            }
        }

        @Nested
        inner class WithNonZeroMaxRetryCount {
            private val maxRetryCount = 2
            private val withRetry = createRetry(maxRetryCount, sleep = mockSleep) {
                true
            }

            @Test
            fun `retries correct number of times`() {
                try {
                    withRetry {
                        mockApi()
                    }
                } catch (t: Throwable) {
                    // nothing to do
                } finally {
                    verify(exactly = maxRetryCount + 1) { mockApi() }
                }
            }

            @Test
            fun `re-throws error if no attempt succeeds`() {
                assertThrows<Exception> {
                    withRetry {
                        mockApi()
                    }
                }
            }

            @Test
            fun `does not throw an error if subsequent attempt succeeds`() {
                every { mockApi() } answers { throw Exception("Test exception") } andThen { }
                assertDoesNotThrow {
                    withRetry {
                        mockApi()
                    }
                }
            }
        }
    }

    @Nested
    inner class Delay {
        private val mockRandom = mockk<(Double, Double) -> Double>()

        @Test
        fun `delays attempts with random duration between 0 and retry delay multiplied by attempt number`() {
            val testDelay = 123
            val testDelayWithJitter1 = 99
            val testDelayWithJitter2 = 99
            every { mockRandom(0.0, testDelay.toDouble()) } returns testDelayWithJitter1.toDouble()
            every { mockRandom(0.0, testDelay.toDouble() * 2) } returns testDelayWithJitter2.toDouble()
            every { mockApi() } answers { throw Exception("Test exception") } andThen { throw Exception("Test exception") } andThen {}

            val withRetry = createRetry(2,
                retryDelayMs = testDelay,
                sleep = mockSleep,
                randomBetween = mockRandom) {
                true
            }

            withRetry {
                mockApi()
            }

            verify {
                mockRandom(0.0, testDelay.toDouble())
                mockSleep(testDelayWithJitter1.toLong())
                mockRandom(0.0, testDelay.toDouble()*2)
                mockSleep(testDelayWithJitter2.toLong())
            }
        }

    }


}
