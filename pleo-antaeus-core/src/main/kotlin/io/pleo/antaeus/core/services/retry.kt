package io.pleo.antaeus.core.services

import kotlin.random.Random

/**
 * Creates a retry function based on parameters. Exponential backoff and jitter are in place to avoid
 * overwhelming the remote API with a retry storm.
 *
 * @param numberOfRetries the number of times the action will be retried excluding the initial attempt.
 * @param isRetriable callback to determine if the action should be retried on a given exception.
 * @param action the action to retry.
 */
fun createRetry(
    numberOfRetries: Long,
    initialDelayMs: Long = 200,
    // TODO: find a more descriptive name for this parameter
    jitterRatio: Double = 0.2,
    isRetriable: (Exception) -> Boolean
): (action: () -> Unit) -> Unit {
    return { action ->
        var retryCount = 0
        while (true) {
            if (retryCount > 0) {
                val baseDelay = retryCount * initialDelayMs
                val jitter = Random.nextDouble(-baseDelay*jitterRatio,baseDelay*jitterRatio)
                Thread.sleep(
                    kotlin.math.ceil(baseDelay+jitter).toLong()
                )
            }
            try {
                action()
                break
            } catch (e: Exception) {
                if (isRetriable(e) && retryCount < numberOfRetries) {
                    retryCount++
                    continue
                }
                throw e
            }
        }
    }
}

