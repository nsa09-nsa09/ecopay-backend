package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.PayoutDto;
import kz.hrms.splitupauth.dto.PayoutMethodDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.PayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;

    @GetMapping("/me")
    public ResponseEntity<List<PayoutDto>> listMine(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
                payoutService.listMine(user).stream().map(PayoutDto::from).toList()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<PayoutDto> get(@AuthenticationPrincipal User user, @PathVariable Long id) {
        return ResponseEntity.ok(PayoutDto.from(payoutService.getMine(user, id)));
    }

    @GetMapping("/methods")
    public ResponseEntity<List<PayoutMethodDto>> listMethods(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(
                payoutService.listMethods(user).stream().map(PayoutMethodDto::from).toList()
        );
    }

    @PostMapping("/methods")
    public ResponseEntity<PayoutMethodDto> registerMethod(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, String> body
    ) {
        String token = body.get("providerCardToken");
        String panMask = body.getOrDefault("panMask", null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PayoutMethodDto.from(payoutService.registerMethod(user, token, panMask)));
    }

    @DeleteMapping("/methods/{id}")
    public ResponseEntity<Void> revokeMethod(
            @AuthenticationPrincipal User user, @PathVariable Long id) {
        payoutService.revokeMethod(user, id);
        return ResponseEntity.noContent().build();
    }
}
