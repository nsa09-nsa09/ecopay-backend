package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.CreateNewsRequest;
import kz.hrms.splitupauth.dto.NewsDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.UpdateNewsRequest;
import kz.hrms.splitupauth.entity.NewsStatus;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * End-to-end coverage of the news module:
 * <ul>
 *   <li>CRUD round-trip (create / list / patch / delete).</li>
 *   <li>Public feed filters to PUBLISHED.</li>
 *   <li>Image upload writes to S3, replace deletes the old key, delete removes
 *       both row and bucket object.</li>
 *   <li>NEWS_* rows land in admin_action_log on every admin write.</li>
 * </ul>
 *
 * S3 is mocked at the {@link S3Client} bean level so we can assert what (bucket,
 * key, content-type) hits the bucket without standing up real R2.
 */
class NewsServiceTest extends AbstractIntegrationTest {

    @Autowired NewsService newsService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    /** Replaces the real S3Client so puts/deletes are observable + side-effect-free. */
    @MockitoBean
    S3Client s3Client;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User admin() {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("news_admin_" + n + "_" + System.nanoTime() + "@t.kz")
                .password("x")
                .displayName("News Admin " + n)
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private CreateNewsRequest createReq(String titleRu, NewsStatus status) {
        CreateNewsRequest req = new CreateNewsRequest();
        req.setTitleRu(titleRu);
        req.setBodyRu("Body for " + titleRu);
        req.setStatus(status);
        return req;
    }

    private byte[] makePng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ===================== CRUD =====================

    @Test
    void create_storesRowAndReturnsDto() {
        User adminUser = admin();
        NewsDto dto = newsService.create(adminUser,
                createReq("Заголовок", NewsStatus.DRAFT), new MockHttpServletRequest());

        assertNotNull(dto.getId());
        assertEquals(NewsStatus.DRAFT, dto.getStatus());
        assertEquals("Заголовок", dto.getTitleRu());
        // DRAFT must not auto-set publishedAt.
        assertNull(dto.getPublishedAt());
    }

    @Test
    void create_publishedStatus_setsPublishedAt() {
        User adminUser = admin();
        NewsDto dto = newsService.create(adminUser,
                createReq("Сразу опубликовано", NewsStatus.PUBLISHED),
                new MockHttpServletRequest());

        assertEquals(NewsStatus.PUBLISHED, dto.getStatus());
        assertNotNull(dto.getPublishedAt(),
                "publishing on create must stamp publishedAt");
    }

    @Test
    void create_requiresAtLeastOneTitle() {
        CreateNewsRequest req = new CreateNewsRequest();
        req.setBodyRu("Тело без заголовка");
        assertThrows(InvalidRequestException.class,
                () -> newsService.create(admin(), req, new MockHttpServletRequest()));
    }

    @Test
    void update_patchStatusToPublished_setsPublishedAt() {
        User adminUser = admin();
        NewsDto draft = newsService.create(adminUser,
                createReq("Черновик", NewsStatus.DRAFT), new MockHttpServletRequest());
        assertNull(draft.getPublishedAt());

        UpdateNewsRequest patch = new UpdateNewsRequest();
        patch.setStatus(NewsStatus.PUBLISHED);
        NewsDto published = newsService.update(draft.getId(), adminUser, patch,
                new MockHttpServletRequest());

        assertEquals(NewsStatus.PUBLISHED, published.getStatus());
        assertNotNull(published.getPublishedAt(),
                "transition to PUBLISHED must stamp publishedAt");
    }

    @Test
    void update_partial_keepsUntouchedFields() {
        User adminUser = admin();
        CreateNewsRequest createReq = new CreateNewsRequest();
        createReq.setTitleRu("RU");
        createReq.setTitleEn("EN");
        createReq.setTitleKz("KZ");
        createReq.setStatus(NewsStatus.PUBLISHED);
        NewsDto created = newsService.create(adminUser, createReq, new MockHttpServletRequest());

        UpdateNewsRequest patch = new UpdateNewsRequest();
        patch.setTitleEn("EN edited");
        // sortOrder not in patch — must not blank out
        NewsDto updated = newsService.update(created.getId(), adminUser, patch,
                new MockHttpServletRequest());

        assertEquals("EN edited", updated.getTitleEn());
        assertEquals("RU", updated.getTitleRu(), "untouched RU stays");
        assertEquals("KZ", updated.getTitleKz(), "untouched KZ stays");
    }

    @Test
    void delete_removesRowAndAttemptsS3Cleanup() throws Exception {
        User adminUser = admin();
        NewsDto created = newsService.create(adminUser,
                createReq("To delete", NewsStatus.DRAFT), new MockHttpServletRequest());

        // Attach an image so we can also verify the S3 delete fires.
        newsService.uploadImage(created.getId(), adminUser,
                new MockMultipartFile("file", "x.png", "image/png", makePng(64, 64)),
                new MockHttpServletRequest());

        newsService.delete(created.getId(), adminUser, new MockHttpServletRequest());

        assertThrows(ResourceNotFoundException.class,
                () -> newsService.adminGet(created.getId()));
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    // ===================== public feed =====================

    @Test
    void publicList_returnsOnlyPublished() {
        User adminUser = admin();
        String marker = "feed-marker-" + SEQ.incrementAndGet();

        newsService.create(adminUser,
                createReq(marker + " published", NewsStatus.PUBLISHED),
                new MockHttpServletRequest());
        newsService.create(adminUser,
                createReq(marker + " draft", NewsStatus.DRAFT),
                new MockHttpServletRequest());
        newsService.create(adminUser,
                createReq(marker + " archived", NewsStatus.ARCHIVED),
                new MockHttpServletRequest());

        PagedResponse<NewsDto> feed = newsService.publicList(0, 50);

        long marked = feed.getItems().stream()
                .filter(n -> n.getTitleRu() != null && n.getTitleRu().contains(marker))
                .count();
        assertEquals(1, marked,
                "public feed must include exactly the PUBLISHED row for this marker");
        assertTrue(feed.getItems().stream().allMatch(n -> n.getStatus() == NewsStatus.PUBLISHED),
                "no DRAFT/ARCHIVED rows must leak into the public feed");
    }

    @Test
    void publicGet_404OnNonPublished() {
        User adminUser = admin();
        NewsDto draft = newsService.create(adminUser,
                createReq("Draft only", NewsStatus.DRAFT), new MockHttpServletRequest());
        assertThrows(ResourceNotFoundException.class,
                () -> newsService.publicGet(draft.getId()));
    }

    // ===================== image upload =====================

    @Test
    void uploadImage_putsToS3_andReplaceDeletesOldKey() throws Exception {
        User adminUser = admin();
        NewsDto created = newsService.create(adminUser,
                createReq("With image", NewsStatus.PUBLISHED), new MockHttpServletRequest());

        // First upload: a single PutObject must hit S3.
        newsService.uploadImage(created.getId(), adminUser,
                new MockMultipartFile("file", "a.png", "image/png", makePng(120, 120)),
                new MockHttpServletRequest());

        ArgumentCaptor<PutObjectRequest> putCap = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(putCap.capture(), any(RequestBody.class));
        String firstKey = putCap.getValue().key();
        assertTrue(firstKey.startsWith("news/"),
                "first uploaded key sits under news/ prefix");

        // Second upload: another put + a delete for the previous key.
        newsService.uploadImage(created.getId(), adminUser,
                new MockMultipartFile("file", "b.png", "image/png", makePng(140, 90)),
                new MockHttpServletRequest());

        ArgumentCaptor<DeleteObjectRequest> delCap = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(delCap.capture());
        assertEquals(firstKey, delCap.getValue().key(),
                "replacing the image must delete the previous key");
    }

    // ===================== audit =====================

    @Test
    void everyAdminWrite_writesAdminActionLogRow() {
        User adminUser = admin();
        long createdBefore = jdbc.queryForObject(
                "select count(*) from admin_action_log where action_type = 'NEWS_CREATED'",
                Long.class);

        NewsDto created = newsService.create(adminUser,
                createReq("Audit me", NewsStatus.DRAFT), new MockHttpServletRequest());

        long createdAfter = jdbc.queryForObject(
                "select count(*) from admin_action_log where action_type = 'NEWS_CREATED'",
                Long.class);
        assertEquals(createdBefore + 1, createdAfter,
                "create must append a NEWS_CREATED row");

        UpdateNewsRequest patch = new UpdateNewsRequest();
        patch.setSortOrder(7);
        newsService.update(created.getId(), adminUser, patch, new MockHttpServletRequest());

        long updatedRows = jdbc.queryForObject(
                "select count(*) from admin_action_log where action_type = 'NEWS_UPDATED' "
                        + "and entity_id = " + created.getId(),
                Long.class);
        assertTrue(updatedRows >= 1, "update must append a NEWS_UPDATED row");

        newsService.delete(created.getId(), adminUser, new MockHttpServletRequest());
        long deletedRows = jdbc.queryForObject(
                "select count(*) from admin_action_log where action_type = 'NEWS_DELETED' "
                        + "and entity_id = " + created.getId(),
                Long.class);
        assertTrue(deletedRows >= 1, "delete must append a NEWS_DELETED row");
    }
}
