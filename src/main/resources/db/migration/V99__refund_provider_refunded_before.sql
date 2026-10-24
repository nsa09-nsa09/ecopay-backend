-- B4: snapshot of the provider's total refunded amount for the parent charge, taken at the moment
-- this refund's revoke/cancel was submitted. Confirmation via get_status3 then needs only
-- providerRefunded_now >= provider_refunded_before + this refund's amount, which stays correct even
-- with several partial refunds on one payment (our own running sum could diverge from the provider).
ALTER TABLE refund_transactions
    ADD COLUMN provider_refunded_before NUMERIC(12, 2);
