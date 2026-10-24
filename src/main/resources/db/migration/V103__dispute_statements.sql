CREATE TABLE dispute_statements (
    id          BIGSERIAL PRIMARY KEY,
    dispute_id  BIGINT  NOT NULL REFERENCES disputes(id),
    author_id   BIGINT  NOT NULL REFERENCES users(id),
    side        VARCHAR(20) NOT NULL,
    text        TEXT    NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP,
    CONSTRAINT uq_dispute_statement_side UNIQUE (dispute_id, side)
);

CREATE INDEX idx_dispute_statements_dispute ON dispute_statements(dispute_id);
