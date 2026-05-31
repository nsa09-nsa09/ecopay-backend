package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.SavedCardDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.SavedCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/payments/saved-cards")
@RequiredArgsConstructor
public class SavedCardController {

    private final SavedCardService savedCardService;

    @GetMapping
    public ResponseEntity<List<SavedCardDto>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
                savedCardService.listActive(user).stream().map(SavedCardDto::from).toList()
        );
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<Void> setDefault(@AuthenticationPrincipal User user, @PathVariable Long id) {
        savedCardService.setDefault(user, id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable Long id) {
        savedCardService.revoke(user, id);
        return ResponseEntity.noContent().build();
    }
}
