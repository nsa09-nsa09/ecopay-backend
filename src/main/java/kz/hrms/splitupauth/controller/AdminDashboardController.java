package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.AdminDashboardKpisDto;
import kz.hrms.splitupauth.dto.DashboardMetricsDto;
import kz.hrms.splitupauth.service.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService service;

    @GetMapping("/kpis")
    public ResponseEntity<AdminDashboardKpisDto> kpis() {
        return ResponseEntity.ok(service.getKpis());
    }

    // The frontend admin dashboard sends dates as yyyy-MM-dd (no time / no Z), so
    // accept LocalDate here. The service widens `from` to start-of-day and `to`
    // to end-of-day before querying. Trying to bind these as LocalDateTime with
    // ISO.DATE_TIME caused MethodArgumentTypeMismatchException → 400 and blew up
    // the dashboard charts.
    @GetMapping("/metrics")
    public ResponseEntity<DashboardMetricsDto> metrics(
            @RequestParam(required = false, defaultValue = "month") String granularity,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return ResponseEntity.ok(service.getMetrics(granularity, from, to));
    }
}
