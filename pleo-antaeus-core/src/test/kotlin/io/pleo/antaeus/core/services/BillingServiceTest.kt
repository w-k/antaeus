package io.pleo.antaeus.core.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.pleo.antaeus.core.exceptions.CurrencyMismatchException
import io.pleo.antaeus.core.exceptions.CustomerNotFoundException
import io.pleo.antaeus.core.exceptions.NetworkException
import io.pleo.antaeus.core.external.PaymentProvider
import io.pleo.antaeus.models.*
import io.pleo.antaeus.models.Currency
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

class BillingServiceTest {

    private val paymentProvider = mockk<PaymentProvider>()
    private val invoiceService = mockk<InvoiceService>(relaxed = true)
    private val clock = mockk<Clock> {
        every { instant() } returns LocalDateTime.of(2021, 1, 1, 0, 2)
            .toInstant(ZoneOffset.UTC)
        every { zone } returns ZoneOffset.UTC
    }
    private val timer = mockk<Timer>(relaxed = true)

    private val billingService = BillingService(paymentProvider, invoiceService, clock, timer)

    @Nested
    inner class Scheduling {
        @Test
        fun `schedules invoice processing for next month`() {
            val mockNow = LocalDateTime.of(2021, 1, 1, 0, 2)
            val expectedScheduleTime = LocalDateTime.of(2021, 2, 1, 0, 1)

            every { clock.instant() } returns mockNow.toInstant(ZoneOffset.UTC)
            billingService.startScheduling()
            verify {
                timer.schedule(any(), Date.from(expectedScheduleTime.toInstant(ZoneOffset.UTC)))
            }
        }

        @Test
        fun `schedules invoice processing for current month`() {
            val mockNow = LocalDateTime.of(2021, 1, 1, 0, 0)
            val expectedScheduleTime = LocalDateTime.of(2021, 1, 1, 0, 1)

            every { clock.instant() } returns mockNow.toInstant(ZoneOffset.UTC)
            billingService.startScheduling()
            verify {
                timer.schedule(any(), Date.from(expectedScheduleTime.toInstant(ZoneOffset.UTC)))
            }
        }
    }

    @Nested
    inner class InvoiceProcessing {
        private val testInvoice = Invoice(
            123, 999, Money(
                BigDecimal.valueOf(100),
                Currency.EUR
            ), InvoiceStatus.PENDING, null
        )

        @BeforeEach
        fun beforeEach() {
            every { timer.schedule(any(), any<Date>()) } answers {
                firstArg<TimerTask>().run()
            } andThen {
            }
            every { invoiceService.fetchWithStatus(InvoiceStatus.PENDING) } returns List<Invoice>(1) {
                testInvoice
            }
            every { paymentProvider.charge(any()) } returns true
        }


        @Test
        fun `fetches pending invoices`() {
            billingService.startScheduling()
            verify {
                invoiceService.fetchWithStatus(InvoiceStatus.PENDING)
            }
        }

        @Test
        fun `calls payment provider`() {
            billingService.startScheduling()
            verify {
                paymentProvider.charge(testInvoice)
            }
        }

        @Test
        fun `updates status to PAID if payment provider returns true`() {
            every { paymentProvider.charge(any()) } returns true
            billingService.startScheduling()
            verify {
                invoiceService.setStatus(testInvoice.id, InvoiceStatus.PAID)
            }
        }

        @Test
        fun `marks invoice as FAILED with reason INSUFFICIENT_FUNDS if payment provider returns false`() {
            every { paymentProvider.charge(any()) } returns false
            billingService.startScheduling()
            verify {
                invoiceService.markAsFailed(testInvoice.id, FailureReason.INSUFFICIENT_FUNDS)
            }
        }

        @Nested
        inner class ErrorHandling {
            @Test
            fun `marks invoice as FAILED with reason UNKNOWN if payment provider throws an unknown error`() {
                every { paymentProvider.charge(any()) } throws Error("Some random error")
                billingService.startScheduling()
                verify {
                    invoiceService.markAsFailed(testInvoice.id, FailureReason.UNKNOWN)
                }
            }

            @Test
            fun `marks invoice as FAILED with reason CUSTOMER_NOT_FOUND if payment provider throws a CustomerNotFoundException`() {
                every { paymentProvider.charge(any()) } throws CustomerNotFoundException(testInvoice.id)
                billingService.startScheduling()
                verify {
                    invoiceService.markAsFailed(testInvoice.id, FailureReason.CUSTOMER_NOT_FOUND)
                }
            }

            @Test
            fun `marks invoice as FAILED with reason CURRENCY_MISMATCH if payment provider throws a CurrencyMismatchException`() {
                every { paymentProvider.charge(any()) } throws CurrencyMismatchException(
                    testInvoice.id,
                    testInvoice.customerId
                )
                billingService.startScheduling()
                verify {
                    invoiceService.markAsFailed(testInvoice.id, FailureReason.CURRENCY_MISMATCH)
                }
            }

            @Test
            fun `sets invoice status to PAID if payment provider throws a NetworkException once`() {
                every { paymentProvider.charge(any()) } answers { throw NetworkException() } andThen { true }
                billingService.startScheduling()
                verify {
                    invoiceService.setStatus(testInvoice.id, InvoiceStatus.PAID)
                }
            }

            @Test
            fun `sets invoice status to PAID if payment provider throws a NetworkException 3 times`() {
                every { paymentProvider.charge(any()) } answers {
                    throw NetworkException()
                } andThen {
                    throw NetworkException()
                } andThen {
                    throw NetworkException()
                } andThen { true }
                billingService.startScheduling()
                verify {
                    invoiceService.setStatus(testInvoice.id, InvoiceStatus.PAID)
                }
            }

            @Test
            fun `marks invoice as FAILED with reason NETWORK if payment provider throws a NetworkException more than 3 times`() {
                every { paymentProvider.charge(any()) } throws NetworkException()
                billingService.startScheduling()
                verify {
                    invoiceService.markAsFailed(testInvoice.id, FailureReason.NETWORK)
                }
            }
        }

    }
}