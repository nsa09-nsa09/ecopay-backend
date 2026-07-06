package kz.hrms.splitupauth.repository;

import java.util.Optional;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FreedomWebhookInboxRepository extends JpaRepository<FreedomWebhookInbox, Long> {

  Optional<FreedomWebhookInbox> findByProviderRequestId(String providerRequestId);
}
