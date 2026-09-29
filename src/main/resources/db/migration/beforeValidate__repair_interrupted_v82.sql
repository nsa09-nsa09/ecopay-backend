-- The first V82 deployment could be interrupted by the old 150-second readiness timeout while
-- CockroachDB index jobs continued in the background. V82 is idempotent, so remove only its failed
-- history entry and let Flyway safely retry it. Successful V82 entries are never changed.
DELETE FROM flyway_schema_history
WHERE version = '82'
  AND success = FALSE;
