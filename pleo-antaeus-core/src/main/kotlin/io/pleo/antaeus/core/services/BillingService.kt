package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus
import kotlinx.coroutines.*

class BillingService(
    private val paymentProvider: PaymentProvider,
    private val invoiceService: InvoiceService
) {
    fun start() = GlobalScope.async {
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

    private val retry =
        createRetry(times = 3, initialDelayMs = 100, jitterRatio = 0.2, isRetriable = { it is NetworkException })

    private fun processSingleChunkSequentially(chunk: List<Invoice>) {
        chunk.forEach {
            // Retrying with the assumption that the provider treats the invoice id as the idempotency token (i.e.
            // that the customer will only be charged once if the requests are repeated with the same token). This
            // assumption is necessary because the network error might happen after the operation was successfully
            // executed on the provider's side but the response got lost. Of course, in real life I would verify such
            // assumption before shipping code.
            retry { processSingleInvoice(it) }
        }
    }

    private fun processSingleInvoice(invoice: Invoice) {
        try {
            // no need to specify currency to payment provider? 🤔
            val paid = paymentProvider.charge(invoice)
            if (paid) {
                println("Successfully charged customer ${invoice.customerId} ${invoice.amount.value} ${invoice.amount.currency}")
                invoiceService.updateStatus(invoice.id, InvoiceStatus.PAID)
            } else {
                // TODO: add failure reason column
                println("Customer ${invoice.customerId} could not be charged ${invoice.amount.value} ${invoice.amount.currency}. Need to update payment details.")
                invoiceService.updateStatus(invoice.id, InvoiceStatus.FAILED)
            }
        } catch (e: CustomerNotFoundException) {
            println("Customer ${invoice.customerId} not found by the payment provider.")
            invoiceService.updateStatus(invoice.id, InvoiceStatus.FAILED)
            throw e
        } catch (e: CurrencyMismatchException) {
            println("The invoice ${invoice.id} issued in the wrong currency.")
            invoiceService.updateStatus(invoice.id, InvoiceStatus.FAILED)
            throw e
        } catch (e: NetworkException) {
            println("Network error when charging invoice ${invoice.id}.")
            invoiceService.updateStatus(invoice.id, InvoiceStatus.FAILED)
            throw e
        } catch (e: Exception) {
            println("Unexpected exception processing invoice ${invoice.toString()}")
            invoiceService.updateStatus(invoice.id, InvoiceStatus.FAILED)
            throw e
        }
    }
}
