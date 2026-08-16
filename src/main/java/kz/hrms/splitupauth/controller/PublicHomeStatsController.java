package kz.hrms.splitupauth.controller;

import java.time.Duration;
import kz.hrms.splitupauth.dto.PublicHomeStatsDto;
import kz.hrms.splitupauth.service.PublicHomeStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicHomeStatsController {

  private final PublicHomeStatsService service;

  @GetMapping("/home-stats")
  public ResponseEntity<PublicHomeStatsDto> homeStats() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
        .body(service.getStats());
  }
}
