CREATE INDEX idx_room_event_log_actor_created_at
    ON room_event_log(actor_user_id, created_at DESC);

CREATE INDEX idx_admin_action_log_entity_created_at
    ON admin_action_log(entity_type, entity_id, created_at DESC);

CREATE INDEX idx_users_deleted_list
    ON users(deleted_at DESC) WHERE status = 'DELETED';
