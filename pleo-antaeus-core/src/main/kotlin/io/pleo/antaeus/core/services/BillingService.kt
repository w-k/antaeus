package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.models.FailureReason
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus
import kotlinx.coroutines.*
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Timer
import java.util.Date
import kotlin.concurrent.schedule

class BillingService(
    private val paymentProvider: PaymentProvider,
    private val invoiceService: InvoiceService,
    private val clock: Clock,
    private val timer: Timer
) {
    fun startScheduling() {
        scheduleNext()
    }

    private fun scheduleNext() {
        val nextExecutionTime = getNextExecutionDate(LocalDateTime.now(clock))
        timer.schedule(nextExecutionTime) {
            scheduleNext()
            processAllPendingInvoices()
        }
    }

    // The invoice processing run will be scheduled on the 1st of every month at 00:01.
    // If current time is before 00:01 on the 1st, schedule for this month, otherwise for next.
    // Scheduling in UTC. In real life this would depend on the contractual obligations with customers (probably
    // their local time zones).
    private fun getNextExecutionDate(now: LocalDateTime): Date {
        val thisMonthsExecution = now.withDayOfMonth(1)
            .withHour(0)
            .withMinute(1)
            .withSecond(0)
            .withNano(0)
        val nextExecution = if (thisMonthsExecution.isAfter(now)) {
            thisMonthsExecution
        } else {
            thisMonthsExecution.plusMonths(1)
        }
        return Date.from(nextExecution.toInstant(ZoneOffset.UTC))
    }

    private fun processAllPendingInvoices() {
        println("processing all pending invoices")
        val pendingInvoices = invoiceService.fetchWithStatus(InvoiceStatus.PENDING)
        // It's an arbitrary number of coroutines that are going to be executed concurrently. The mock
        // PaymentProvider#charge is blocking so in this case it should correspond to the number of cores
        // in the environment. However, in real life the PaymentProvider would make remote asynchronous API
        // calls and be non-blocking so a value higher than the number of cores might be optimal.
        val concurrentChunks = 10
        val chunkSize = kotlin.math.ceil(pendingInvoices.size / concurrentChunks.toDouble()).toInt()
        val chunks = pendingInvoices.chunked(chunkSize)
        // TODO: throttle the payment provider request frequency to ensure we don't hit their API's rate limit
        processMultipleChunksInParallel(chunks)
        println("all invoices processed")
    }

    private fun processMultipleChunksInParallel(chunks: List<List<Invoice>>) = runBlocking {
        chunks.map {
            launch {
                processSingleChunkSequentially(it)
                println("processed chunk")
            }
        }
    }

    private fun processSingleChunkSequentially(chunk: List<Invoice>) {
        chunk.forEach {
            processSingleInvoice(it)
        }
    }

    private val withRetry =
        createRetry(maxRetryCount = 3, retryDelayMs = 100, canBeRetried = { it is NetworkException })

    private fun processSingleInvoice(invoice: Invoice) {
        try {
            // Retrying with the assumption that the provider treats the invoice id as the idempotency token (i.e.
            // that the customer will only be charged once if the requests are repeated with the same token). This
            // assumption is necessary because the network error might happen after the operation was successfully
            // executed on the provider's side but the response got lost. Of course, in real life I would verify such
            // assumption before shipping code.
            withRetry {
                println("processing invoice with retries")
                val paid = paymentProvider.charge(invoice)
                if (paid) {
                    println("Successfully charged customer ${invoice.customerId} ${invoice.amount.value} ${invoice.amount.currency}")
                    invoiceService.setStatus(invoice.id, InvoiceStatus.PAID)
                } else {
                    // TODO: add failure reason column
                    println("Customer ${invoice.customerId} could not be charged ${invoice.amount.value} ${invoice.amount.currency}. Need to update payment details.")
                    invoiceService.markAsFailed(invoice.id, FailureReason.INSUFFICIENT_FUNDS)
                }
            }
        } catch (e: CustomerNotFoundException) {
            println("Customer ${invoice.customerId} not found by the payment provider.")
            invoiceService.markAsFailed(invoice.id, FailureReason.CUSTOMER_NOT_FOUND)
        } catch (e: CurrencyMismatchException) {
            println("The invoice ${invoice.id} issued in the wrong currency.")
            invoiceService.markAsFailed(invoice.id, FailureReason.CURRENCY_MISMATCH)
        } catch (e: NetworkException) {
            println("Network error when charging invoice ${invoice.id}.")
            invoiceService.markAsFailed(invoice.id, FailureReason.NETWORK)
        } catch (e: Throwable) {
            println("Unexpected exception processing invoice $invoice")
            invoiceService.markAsFailed(invoice.id, FailureReason.UNKNOWN)
        }
    }
}
