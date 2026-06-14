package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.dto.AdminServiceReviewDto;
import kz.hrms.splitupauth.dto.AdminUpdateServiceReviewRequest;
import kz.hrms.splitupauth.dto.CreateServiceReviewRequest;
import kz.hrms.splitupauth.dto.ServiceReviewDto;
import kz.hrms.splitupauth.dto.UpdateServiceReviewRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.ServiceReview;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.ServiceReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceReviewServiceTest {

    @Mock private ServiceReviewRepository repository;
    @Mock private AdminActionLogRepository adminActionLogRepository;
    @Mock private HttpServletRequest http;

    private ServiceReviewService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User author(long id) {
        return User.builder()
                .id(id)
                .email("u" + id + "@e.kz")
                .displayName("User " + id)
                .publicId("publ" + id)
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }

    private User admin() {
        return User.builder().id(99L).email("a@e.kz").role(Role.ADMIN)
                .status(UserStatus.ACTIVE).build();
    }

    @BeforeEach
    void setUp() {
        service = new ServiceReviewService(repository, adminActionLogRepository, objectMapper);
    }

    @Test
    void createMine_storesSanitizedText_andFeaturedFalse() {
        User user = author(1L);
        when(repository.existsByAuthor(user)).thenReturn(false);
        when(repository.save(any(ServiceReview.class))).thenAnswer(inv -> {
            ServiceReview r = inv.getArgument(0);
            r.setId(10L);
            return r;
        });

        CreateServiceReviewRequest req = new CreateServiceReviewRequest();
        req.setRating(5);
        req.setText("Отличный <script>alert('xss')</script> сервис!");

        ServiceReviewDto dto = service.createMine(user, req);

        ArgumentCaptor<ServiceReview> cap = ArgumentCaptor.forClass(ServiceReview.class);
        verify(repository).save(cap.capture());
        ServiceReview saved = cap.getValue();
        assertFalse(saved.getText().contains("<"));
        assertFalse(saved.getFeatured());
        assertEquals(5, dto.getRating());
        assertNotNull(dto.getAuthorPublicId());
    }

    @Test
    void createMine_rejectsDuplicate_with409() {
        User user = author(2L);
        when(repository.existsByAuthor(user)).thenReturn(true);

        CreateServiceReviewRequest req = new CreateServiceReviewRequest();
        req.setRating(5);
        req.setText("dup");

        assertThrows(ResourceConflictException.class, () -> service.createMine(user, req));
        verify(repository, never()).save(any());
    }

    @Test
    void updateMine_clearsFeatured() {
        User user = author(3L);
        ServiceReview existing = ServiceReview.builder()
                .id(20L).author(user).rating(4).text("ok").featured(true).build();
        when(repository.findByAuthor(user)).thenReturn(Optional.of(existing));
        when(repository.save(any(ServiceReview.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateServiceReviewRequest req = new UpdateServiceReviewRequest();
        req.setRating(3);
        req.setText("changed mind");

        ServiceReviewDto dto = service.updateMine(user, req);

        assertEquals(3, dto.getRating());
        assertFalse(dto.getFeatured(), "editing must reset featured for re-moderation");
    }

    @Test
    void setFeatured_logsCorrectActionType_andTogglesFlag() {
        User adminUser = admin();
        ServiceReview existing = ServiceReview.builder()
                .id(33L).author(author(4L)).rating(5).text("nice").featured(false).build();
        when(repository.findById(33L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ServiceReview.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminServiceReviewDto dto = service.setFeatured(33L, true, adminUser, http);

        assertEquals(true, dto.getFeatured());
        ArgumentCaptor<AdminActionLog> logCap = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(logCap.capture());
        assertEquals(AdminActionType.TESTIMONIAL_FEATURED, logCap.getValue().getActionType());

        // Toggle off
        service.setFeatured(33L, false, adminUser, http);
        verify(adminActionLogRepository, org.mockito.Mockito.times(2)).save(logCap.capture());
        assertEquals(AdminActionType.TESTIMONIAL_UNFEATURED, logCap.getValue().getActionType());
    }

    @Test
    void adminUpdate_logs_TESTIMONIAL_EDITED() {
        User adminUser = admin();
        ServiceReview existing = ServiceReview.builder()
                .id(50L).author(author(5L)).rating(4).text("orig").featured(true).build();
        when(repository.findById(50L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ServiceReview.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminUpdateServiceReviewRequest req = new AdminUpdateServiceReviewRequest();
        req.setText("corrected typo");

        service.adminUpdate(50L, req, adminUser, http);

        ArgumentCaptor<AdminActionLog> logCap = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(logCap.capture());
        assertEquals(AdminActionType.TESTIMONIAL_EDITED, logCap.getValue().getActionType());
    }

    @Test
    void adminDelete_removes_andLogs() {
        User adminUser = admin();
        ServiceReview existing = ServiceReview.builder()
                .id(60L).author(author(6L)).rating(2).text("bad").featured(false).build();
        when(repository.findById(60L)).thenReturn(Optional.of(existing));

        service.adminDelete(60L, adminUser, http);

        verify(repository).delete(existing);
        ArgumentCaptor<AdminActionLog> logCap = ArgumentCaptor.forClass(AdminActionLog.class);
        verify(adminActionLogRepository).save(logCap.capture());
        assertEquals(AdminActionType.TESTIMONIAL_DELETED, logCap.getValue().getActionType());
    }

    @Test
    void setFeatured_404WhenMissing() {
        when(repository.findById(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.setFeatured(404L, true, admin(), http));
    }
}
