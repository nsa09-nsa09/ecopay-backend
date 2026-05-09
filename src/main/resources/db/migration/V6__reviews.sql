-- Phase 8: Reviews and reputation.

CREATE TABLE IF NOT EXISTS reviews (
    id              BIGSERIAL    PRIMARY KEY,
    author_id       BIGINT       NOT NULL REFERENCES users(id),
    recipient_id    BIGINT       NOT NULL REFERENCES users(id),
    room_id         BIGINT       NOT NULL REFERENCES rooms(id),
    rating          INT          NOT NULL CHECK (rating BETWEEN 1 AND 5),
    text            TEXT,
    hidden_by_admin BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reviews_author_recipient_room
        UNIQUE (author_id, recipient_id, room_id)
);

CREATE INDEX IF NOT EXISTS idx_reviews_recipient_created
    ON reviews (recipient_id, created_at DESC);
