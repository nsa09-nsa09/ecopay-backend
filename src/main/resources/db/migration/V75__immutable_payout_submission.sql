ALTER TABLE payouts
    ADD COLUMN submitted_amount NUMERIC(12,2),
    ADD COLUMN provider_order_id VARCHAR(50),
    ADD COLUMN clawback_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN clawback_amount NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (clawback_amount >= 0);

ALTER TABLE payout_batches
    ADD COLUMN provider_order_id VARCHAR(50),
    ADD COLUMN destination_card_token VARCHAR(255),
    ADD COLUMN provider_name VARCHAR(50),
    ADD COLUMN submission_started_at TIMESTAMP;

-- Preserve the order IDs used by the old gateway for reconciliation, including ambiguous attempts.
UPDATE payouts SET provider_order_id = id::text;
UPDATE payouts SET submitted_amount = amount
 WHERE payout_batch_id IS NOT NULL OR status IN ('PROCESSING','PENDING_PROVIDER','SUCCESS','REQUIRES_REVIEW')
    OR retry_count > 0 OR provider_payout_id IS NOT NULL;
UPDATE payout_batches b SET provider_order_id = b.id::text,
    destination_card_token = m.provider_card_token, provider_name = m.provider_name,
    submission_started_at = b.created_at
 FROM payout_methods m WHERE m.id = b.payout_method_id;

-- An old PROCESSING batch has no provable submission payload: never replay it automatically.
UPDATE payout_batches SET status = 'REQUIRES_REVIEW',
    failure_reason = 'Legacy batch: reconcile provider amount and order ID before settlement',
    next_retry_at = NULL, lease_until = NULL
 WHERE status = 'PROCESSING';
UPDATE payouts p SET status = 'REQUIRES_REVIEW', failure_reason = b.failure_reason,
    next_retry_at = NULL, lease_until = NULL
 FROM payout_batches b WHERE p.payout_batch_id = b.id AND b.status = 'REQUIRES_REVIEW'
    AND p.status <> 'SUCCESS';

-- Fail rather than invent an allocation for legacy batches whose child amounts no longer agree.
DO $$ BEGIN
  IF EXISTS (SELECT b.id FROM payout_batches b LEFT JOIN payouts p ON p.payout_batch_id = b.id
             GROUP BY b.id HAVING COALESCE(SUM(p.submitted_amount), 0) <> b.amount) THEN
    RAISE EXCEPTION 'Legacy payout batch allocation mismatch: reconcile against provider before migrating';
  END IF;
END $$;

ALTER TABLE payouts ADD CONSTRAINT chk_payout_submitted_amount
    CHECK ((submitted_amount IS NULL OR submitted_amount > 0)
           AND (payout_batch_id IS NULL OR submitted_amount IS NOT NULL));
CREATE UNIQUE INDEX uq_payout_provider_order_id ON payouts(provider_order_id);
CREATE UNIQUE INDEX uq_batch_provider_order_id ON payout_batches(provider_order_id);

CREATE FUNCTION enforce_payout_submission_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.submitted_amount IS NOT NULL AND
     (NEW.submitted_amount IS DISTINCT FROM OLD.submitted_amount
      OR NEW.payout_batch_id IS DISTINCT FROM OLD.payout_batch_id
      OR NEW.currency IS DISTINCT FROM OLD.currency
      OR NEW.user_id IS DISTINCT FROM OLD.user_id
      OR NEW.payout_method_id IS DISTINCT FROM OLD.payout_method_id
      OR NEW.provider_order_id IS DISTINCT FROM OLD.provider_order_id
      OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key) THEN
    RAISE EXCEPTION 'Submitted payout payload is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_payout_submission_immutable BEFORE UPDATE ON payouts
    FOR EACH ROW EXECUTE FUNCTION enforce_payout_submission_immutable();

CREATE FUNCTION enforce_batch_submission_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.submission_started_at IS NOT NULL AND
     (NEW.amount IS DISTINCT FROM OLD.amount
      OR NEW.currency IS DISTINCT FROM OLD.currency
      OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
      OR NEW.payout_method_id IS DISTINCT FROM OLD.payout_method_id
      OR NEW.destination_card_token IS DISTINCT FROM OLD.destination_card_token
      OR NEW.provider_name IS DISTINCT FROM OLD.provider_name
      OR (OLD.provider_order_id IS NOT NULL AND NEW.provider_order_id IS DISTINCT FROM OLD.provider_order_id)
      OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
      OR NEW.submission_started_at IS DISTINCT FROM OLD.submission_started_at) THEN
    RAISE EXCEPTION 'Submitted batch payload is immutable';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_batch_submission_immutable BEFORE UPDATE ON payout_batches
    FOR EACH ROW EXECUTE FUNCTION enforce_batch_submission_immutable();

CREATE FUNCTION check_payout_batch_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE batch_id BIGINT; expected NUMERIC; actual NUMERIC;
BEGIN
  IF TG_TABLE_NAME = 'payout_batches' THEN
    batch_id := NEW.id;
  ELSE
    batch_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.payout_batch_id ELSE NEW.payout_batch_id END;
  END IF;
  IF batch_id IS NULL THEN RETURN NULL; END IF;
  SELECT amount INTO expected FROM payout_batches WHERE id = batch_id FOR UPDATE;
  SELECT COALESCE(SUM(submitted_amount), 0) INTO actual FROM payouts WHERE payout_batch_id = batch_id;
  IF actual <> expected THEN
    RAISE EXCEPTION 'Frozen child amounts (%) differ from batch % amount (%)', actual, batch_id, expected;
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_batch_allocation AFTER INSERT OR UPDATE ON payout_batches
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payout_batch_allocation();
CREATE CONSTRAINT TRIGGER trg_child_allocation AFTER INSERT OR UPDATE OF submitted_amount, payout_batch_id OR DELETE ON payouts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_payout_batch_allocation();
