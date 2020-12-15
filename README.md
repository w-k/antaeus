# Antaeus - Pawel's Readme

_See original readme [here](README.md)._

## Billing Service

The billing service schedules monthly invoice processing on application
start up. When the time comes, all pending invoices are processed in 10 
parallel coroutines. 

Network errors from payment provider are retried 3 times 
with [exponential backoff and jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/). 
All other errors are treated as not retriable and fail the first time. Both errors and the insufficient
funds response from payment provider result in the invoice status change to `FAILED` and setting
the relevant `FailureReason` in the Invoice table. 

## Assumptions

* `PaymentProvider` is an external remote API and therefore susceptible to 
potentially recoverable network issues.

* `PaymentProvider` operations can be safely retried without the
risk of double-charging customers.
  
* Invoices will be processed on the 1st of every month at 00:01:00 UTC

* All pending invoices are due and should be processed on the 1st.
  

## Out of Scope

* Following up on errors from payment provider. For example
  updating customer currency and recalculating invoice amounts.
  
* Graceful handling of application restart during invoice processing.
  
## Next steps

* Add rate-limiting of the `PaymentProvider`'s `charge` api
  
* Persist the queue of invoices at the start of processing 
  to ensure correct behaviour between application restarts
  
* Introduce the `Payment` and `PaymentBatch` resources and
expose them through a REST api


