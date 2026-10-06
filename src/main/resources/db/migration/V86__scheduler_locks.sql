-- Cluster-wide leases for @Scheduled jobs (SchedulerLock). A row is (re)acquired only when its
-- lease has expired, so a crashed replica can block a job for at most its lockAtMostFor.
CREATE TABLE IF NOT EXISTS scheduler_locks (
    name         VARCHAR(100) PRIMARY KEY,
    locked_until TIMESTAMP    NOT NULL,
    locked_at    TIMESTAMP    NOT NULL,
    locked_by    VARCHAR(255) NOT NULL
);
