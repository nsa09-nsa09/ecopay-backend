CREATE TABLE room_settings (
    id BIGINT PRIMARY KEY,
    minimum_room_members INTEGER NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT chk_room_settings_singleton CHECK (id = 1),
    CONSTRAINT chk_room_settings_minimum_room_members CHECK (minimum_room_members >= 2)
);

INSERT INTO room_settings (id, minimum_room_members, updated_at)
VALUES (1, 5, CURRENT_TIMESTAMP);
