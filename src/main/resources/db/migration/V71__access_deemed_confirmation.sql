ALTER TABLE room_members
    ADD COLUMN IF NOT EXISTS access_confirmation_deadline_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS access_deemed_confirmed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_room_members_access_confirmation_due
    ON room_members(status, access_confirmation_deadline_at)
    WHERE deleted_at IS NULL
      AND owner_access_confirmed_at IS NOT NULL
      AND member_confirmed_at IS NULL
      AND access_deemed_confirmed_at IS NULL;

-- Historical records receive no deadline and are never silently auto-confirmed.
