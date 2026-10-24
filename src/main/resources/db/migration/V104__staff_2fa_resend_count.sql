ALTER TABLE staff_two_factor_challenges
    ADD COLUMN resend_count INTEGER NOT NULL DEFAULT 0;
