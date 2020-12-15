package io.pleo.antaeus.core.services

import kotlin.math.pow
import kotlin.random.Random

/**
 * Creates a retry function based on parameters. Exponential backoff and full jitter are in place to avoid
 * overwhelming the remote API with a retry storm. See this blog post for more information on these concepts:
 * https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
 *
 * @param maxRetryCount the number of times the action will be retried excluding the initial attempt.
 * @param retryDelayMs the initial delay in milliseconds which will increase exponentially on subsequent attempts.
 * @param sleep optional sleep function. Can be injected for testing.
 * @param randomBetween optional random function. Can be injected for testing.
 * @param canBeRetried function to determine if the action should be retried on a given error or exception.
 * @param action the action to retry.
 */
fun createRetry(
    maxRetryCount: Int,
    retryDelayMs: Int = 100,
    sleep: (Long) -> Unit = Thread::sleep,
    randomBetween: (Double, Double) -> Double = Random.Default::nextDouble,
    canBeRetried: (Throwable) -> Boolean
): (action: () -> Unit) -> Unit {
    return { action ->
        try {
            // Executing the action for the first time without delay.
            action()
        } catch (t: Throwable) {
            var retryCount = 0
            while (true) {
                if (!canBeRetried(t) || retryCount >= maxRetryCount) {
                    throw t
                }
                val delay = 2.0.pow(retryCount) * retryDelayMs
                val delayWithJitter = randomBetween(0.0, delay)
                sleep(
                    kotlin.math.ceil(delayWithJitter).toLong()
                )
                try {
                    action()
                    // Action executed without errors, breaking out of the loop.
                    break
                } catch (t: Throwable) {
                    retryCount++
                }
            }
        }
    }
}

