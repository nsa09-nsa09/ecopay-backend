-- B10: room_members gets a new terminal status ROOM_CANCELLED for members who had paid when
-- their room was cancelled by the owner. Previously these rows were set to
-- CANCELLED_BEFORE_PAYMENT, which was factually wrong (there WAS a payment) and made it hard to
-- distinguish an owner cancellation from a member backing out before paying.

ALTER TABLE room_members
    DROP CONSTRAINT IF EXISTS chk_room_members_status;

ALTER TABLE room_members
    ADD CONSTRAINT chk_room_members_status
    CHECK (status IN (
           'APPLIED',
           'PENDING',
           'ACTIVE',
           'REJECTED',
           'CANCELLED_BEFORE_PAYMENT',
           'ROOM_CANCELLED',
           'BLOCKED_BY_ADMIN'
                     ));
