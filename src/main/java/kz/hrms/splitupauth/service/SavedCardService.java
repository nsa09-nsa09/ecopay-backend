package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.SavedCard;
import kz.hrms.splitupauth.entity.SavedCardStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.SavedCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SavedCardService {

    private final SavedCardRepository repository;

    @Transactional(readOnly = true)
    public List<SavedCard> listActive(User user) {
        return repository.findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(
                user, SavedCardStatus.ACTIVE);
    }

    @Transactional
    public SavedCard upsertSavedCard(User user, String providerName,
                                     String providerToken, String panMask) {
        SavedCard existing = repository.findByUserAndProviderTokenAndProviderName(
                user, providerToken, providerName).orElse(null);
        if (existing != null) {
            existing.setStatus(SavedCardStatus.ACTIVE);
            existing.setPanMask(panMask);
            return repository.save(existing);
        }
        boolean firstCard = repository
                .findByUserAndIsDefaultTrueAndStatus(user, SavedCardStatus.ACTIVE).isEmpty();
        SavedCard card = SavedCard.builder()
                .user(user)
                .providerName(providerName)
                .providerToken(providerToken)
                .panMask(panMask)
                .isDefault(firstCard)
                .status(SavedCardStatus.ACTIVE)
                .build();
        return repository.save(card);
    }

    @Transactional
    public void revoke(User user, Long cardId) {
        SavedCard card = repository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card not found"));
        if (!card.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Not your card");
        }
        card.setStatus(SavedCardStatus.REVOKED);
        card.setRevokedAt(LocalDateTime.now());
        card.setIsDefault(false);
        repository.save(card);
    }

    @Transactional
    public void setDefault(User user, Long cardId) {
        SavedCard card = repository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card not found"));
        if (!card.getUser().getId().equals(user.getId())) {
            throw new ForbiddenOperationException("Not your card");
        }
        if (card.getStatus() != SavedCardStatus.ACTIVE) {
            throw new ResourceNotFoundException("Card is not active");
        }
        repository.findByUserAndIsDefaultTrueAndStatus(user, SavedCardStatus.ACTIVE)
                .ifPresent(prev -> {
                    if (!prev.getId().equals(cardId)) {
                        prev.setIsDefault(false);
                        repository.save(prev);
                    }
                });
        card.setIsDefault(true);
        repository.save(card);
    }
}
