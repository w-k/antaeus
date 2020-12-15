package io.pleo.antaeus.core.services

import io.mockk.classMockk
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.pleo.antaeus.core.exceptions.InvoiceNotFoundException
import io.pleo.antaeus.data.AntaeusDal
import io.pleo.antaeus.models.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class InvoiceServiceTest {
    private val testInvoices = List(2) { Invoice(
            id = it,
            customerId = it,
            amount = Money(BigDecimal.valueOf(123), Currency.EUR),
            status = InvoiceStatus.PENDING,
            failureReason = null
        ) }
    private val dal = mockk<AntaeusDal> {
        every { fetchInvoice(404) } returns null
        every { fetchInvoices(InvoiceStatus.PENDING)} returns testInvoices
        every { setStatus(any<Int>(), any<InvoiceStatus>()) } returns Unit
        every { setFailureReason(any<Int>(), any<FailureReason>()) } returns Unit
    }

    private val invoiceService = InvoiceService(dal = dal)

    @Test
    fun `will throw if invoice is not found`() {
        assertThrows<InvoiceNotFoundException> {
            invoiceService.fetch(404)
        }
    }

    @Test
    fun `fetches invoices with given status`() {
        val actualInvoices = invoiceService.fetchWithStatus(InvoiceStatus.PENDING)
        assert(actualInvoices == testInvoices)
        verify {
            dal.fetchInvoices(InvoiceStatus.PENDING)
        }
    }

    @Test
    fun `sets invoice status`() {
        invoiceService.setStatus(666, InvoiceStatus.PAID)
        verify {
            dal.setStatus(666, InvoiceStatus.PAID)
        }
    }

    @Test
    fun `marks invoice as failed with a given reason`() {
        invoiceService.markAsFailed(666, FailureReason.INSUFFICIENT_FUNDS)
        verify {
            dal.setStatus(666, InvoiceStatus.FAILED)
            dal.setFailureReason(666, FailureReason.INSUFFICIENT_FUNDS)
        }

    }
}
