/*
    Implements endpoints related to invoices.
 */

package io.pleo.antaeus.core.services

import io.pleo.antaeus.core.exceptions.InvoiceNotFoundException
import io.pleo.antaeus.data.AntaeusDal
import io.pleo.antaeus.models.FailureReason
import io.pleo.antaeus.models.Invoice
import io.pleo.antaeus.models.InvoiceStatus

class InvoiceService(private val dal: AntaeusDal) {
    fun fetchAll(): List<Invoice> {
        return dal.fetchInvoices()
    }

    fun fetchWithStatus(status: InvoiceStatus): List<Invoice> {
        return dal.fetchInvoices(status)
    }

    fun fetch(id: Int): Invoice {
        return dal.fetchInvoice(id) ?: throw InvoiceNotFoundException(id)
    }

    fun setStatus(id: Int, status: InvoiceStatus) {
        dal.setStatus(id, status)
    }

    fun markAsFailed(id: Int, failureReason: FailureReason) {
        dal.setStatus(id, InvoiceStatus.FAILED)
        dal.setFailureReason(id, failureReason)
    }
}
