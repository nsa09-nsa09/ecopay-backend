-- Deliberately fail on invalid legacy rows: do not silently change occupied seats or pricing.
ALTER TABLE rooms ADD CONSTRAINT chk_rooms_at_most_two_existing_members
    CHECK (existing_members_count BETWEEN 1 AND 2 AND existing_members_count < max_members);
