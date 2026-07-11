-- =========================================================
-- V46 — Room chat
--
-- A room opens a chat once guests have paid. Participants are the room owner
-- plus any room_member whose status is PENDING or ACTIVE (i.e. payment SUCCESS
-- has moved them past APPLIED). Messages are persisted here so history survives
-- reconnects; live delivery rides the STOMP topic /topic/rooms/{id}/chat.
--
-- sender_user_id is kept even after a user is soft-deleted/anonymized so the
-- transcript stays coherent; the DTO falls back to a placeholder display name.
-- =========================================================

CREATE TABLE room_chat_messages (
    id             BIGSERIAL PRIMARY KEY,
    room_id        BIGINT      NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    sender_user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body           TEXT        NOT NULL,
    created_at     TIMESTAMP   NOT NULL DEFAULT now()
);

-- Backs the paginated history query (newest-first within a room).
CREATE INDEX idx_room_chat_messages_room_created
    ON room_chat_messages (room_id, created_at DESC);
