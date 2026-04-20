package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {
    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentIntent> findFirstByRoomMemberOrderByCreatedAtDesc(RoomMember roomMember);
}