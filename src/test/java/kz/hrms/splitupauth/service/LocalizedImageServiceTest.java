package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import kz.hrms.splitupauth.dto.NewsDto;
import kz.hrms.splitupauth.dto.StoryDto;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.News;
import kz.hrms.splitupauth.entity.Story;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.repository.NewsRepository;
import kz.hrms.splitupauth.repository.StoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

class LocalizedImageServiceTest {

  private final User admin = new User();
  private final MockHttpServletRequest http = new MockHttpServletRequest();
  private final MockMultipartFile file =
      new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1});

  @Test
  void newsLegacyAndLocalizedUrlsUseCorrectKeysAndFallbackOrder() {
    NewsRepository repository = mock(NewsRepository.class);
    NewsImageStorageService storage = mock(NewsImageStorageService.class);
    News news = News.builder().id(1L).imageKey("news/shared.jpg").build();
    when(repository.findById(1L)).thenReturn(Optional.of(news));
    when(storage.publicUrl(anyString())).thenAnswer(call -> "url/" + call.getArgument(0));
    NewsService service =
        new NewsService(repository, mock(NewsAuditWriter.class), storage, new ObjectMapper());

    NewsDto legacy = service.adminGet(1L);
    assertEquals("url/news/shared.jpg", legacy.getImageUrl());
    assertNull(legacy.getImageUrlKz());
    assertNull(legacy.getImageUrlRu());
    assertNull(legacy.getImageUrlEn());

    news.setImageKey(null);
    news.setImageKeyEn("news/en.jpg");
    news.setImageKeyKz("news/kz.jpg");
    assertEquals("url/news/kz.jpg", service.adminGet(1L).getImageUrl());
    news.setImageKeyRu("news/ru.jpg");
    NewsDto localized = service.adminGet(1L);
    assertEquals("url/news/ru.jpg", localized.getImageUrl());
    assertEquals("url/news/kz.jpg", localized.getImageUrlKz());
    assertEquals("url/news/ru.jpg", localized.getImageUrlRu());
    assertEquals("url/news/en.jpg", localized.getImageUrlEn());
  }

  @Test
  void newsImageOperationsKeepOtherLocalesAndCleanUpAllKeys() {
    NewsRepository repository = mock(NewsRepository.class);
    NewsAuditWriter audit = mock(NewsAuditWriter.class);
    NewsImageStorageService storage = mock(NewsImageStorageService.class);
    News news =
        News.builder()
            .id(1L)
            .imageKey("news/shared.jpg")
            .imageKeyKz("news/kz.jpg")
            .imageKeyRu("news/old-ru.jpg")
            .imageKeyEn("news/en.jpg")
            .build();
    when(repository.findById(1L)).thenReturn(Optional.of(news));
    when(repository.save(any(News.class))).thenAnswer(call -> call.getArgument(0));
    when(storage.store(file))
        .thenReturn("news/new-shared.jpg", "news/new-ru.jpg", "news/newer-ru.jpg");
    NewsService service = new NewsService(repository, audit, storage, new ObjectMapper());

    service.uploadImage(1L, admin, file, http);
    assertEquals("news/new-shared.jpg", news.getImageKey());
    assertEquals("news/kz.jpg", news.getImageKeyKz());
    assertEquals("news/old-ru.jpg", news.getImageKeyRu());
    assertEquals("news/en.jpg", news.getImageKeyEn());
    verify(storage).deleteIfManaged("news/shared.jpg");

    service.uploadImage(1L, "ru", admin, file, http);
    assertEquals("news/new-ru.jpg", news.getImageKeyRu());
    assertEquals("news/new-shared.jpg", news.getImageKey());
    assertEquals("news/kz.jpg", news.getImageKeyKz());
    assertEquals("news/en.jpg", news.getImageKeyEn());
    verify(storage).deleteIfManaged("news/old-ru.jpg");

    service.uploadImage(1L, "ru", admin, file, http);
    verify(storage).deleteIfManaged("news/new-ru.jpg");
    service.deleteImage(1L, "ru", admin, http);
    assertNull(news.getImageKeyRu());
    verify(storage).deleteIfManaged("news/newer-ru.jpg");

    service.deleteImage(1L, admin, http);
    assertNull(news.getImageKey());
    assertEquals("news/kz.jpg", news.getImageKeyKz());
    assertEquals("news/en.jpg", news.getImageKeyEn());
    verify(storage).deleteIfManaged("news/new-shared.jpg");

    service.delete(1L, admin, http);
    verify(storage).deleteIfManaged("news/kz.jpg");
    verify(storage).deleteIfManaged("news/en.jpg");

    ArgumentCaptor<ObjectNode> oldState = ArgumentCaptor.forClass(ObjectNode.class);
    ArgumentCaptor<ObjectNode> newState = ArgumentCaptor.forClass(ObjectNode.class);
    verify(audit, atLeastOnce())
        .writeOrSwallow(
            eq(admin),
            eq(AdminActionType.NEWS_UPDATED),
            eq(1L),
            oldState.capture(),
            newState.capture(),
            eq(http));
    assertTrue(
        newState.getAllValues().stream()
            .anyMatch(node -> node.has("imageKeyRu") && !node.has("imageKey")));
  }

  @Test
  void newsHardDeleteRemovesEveryDistinctKeyAndSnapshotsLocalizedFields() {
    NewsRepository repository = mock(NewsRepository.class);
    NewsAuditWriter audit = mock(NewsAuditWriter.class);
    NewsImageStorageService storage = mock(NewsImageStorageService.class);
    News news =
        News.builder()
            .id(1L)
            .imageKey("news/shared.jpg")
            .imageKeyKz("news/kz.jpg")
            .imageKeyRu("news/shared.jpg")
            .imageKeyEn("news/en.jpg")
            .build();
    when(repository.findById(1L)).thenReturn(Optional.of(news));
    NewsService service = new NewsService(repository, audit, storage, new ObjectMapper());

    service.delete(1L, admin, http);

    verify(storage, times(1)).deleteIfManaged("news/shared.jpg");
    verify(storage).deleteIfManaged("news/kz.jpg");
    verify(storage).deleteIfManaged("news/en.jpg");
    ArgumentCaptor<ObjectNode> snapshot = ArgumentCaptor.forClass(ObjectNode.class);
    verify(audit)
        .writeOrSwallow(
            eq(admin),
            eq(AdminActionType.NEWS_DELETED),
            eq(1L),
            snapshot.capture(),
            eq(null),
            eq(http));
    assertEquals("news/shared.jpg", snapshot.getValue().get("imageKeyRu").asText());
    assertEquals("news/kz.jpg", snapshot.getValue().get("imageKeyKz").asText());
    assertEquals("news/en.jpg", snapshot.getValue().get("imageKeyEn").asText());
  }

  @Test
  void storyLegacyAndLocalizedUrlsUseCorrectKeysAndFallbackOrder() {
    StoryRepository repository = mock(StoryRepository.class);
    StoryImageStorageService storage = mock(StoryImageStorageService.class);
    Story story = Story.builder().id(1L).imageKey("stories/shared.jpg").build();
    when(repository.findById(1L)).thenReturn(Optional.of(story));
    when(storage.publicUrl(anyString())).thenAnswer(call -> "url/" + call.getArgument(0));
    StoryService service =
        new StoryService(repository, mock(StoryAuditWriter.class), storage, new ObjectMapper());

    StoryDto legacy = service.adminGet(1L);
    assertEquals("url/stories/shared.jpg", legacy.getImageUrl());
    assertNull(legacy.getImageUrlKz());
    assertNull(legacy.getImageUrlRu());
    assertNull(legacy.getImageUrlEn());

    story.setImageKey(null);
    story.setImageKeyEn("stories/en.jpg");
    story.setImageKeyKz("stories/kz.jpg");
    assertEquals("url/stories/kz.jpg", service.adminGet(1L).getImageUrl());
    story.setImageKeyRu("stories/ru.jpg");
    StoryDto localized = service.adminGet(1L);
    assertEquals("url/stories/ru.jpg", localized.getImageUrl());
    assertEquals("url/stories/kz.jpg", localized.getImageUrlKz());
    assertEquals("url/stories/ru.jpg", localized.getImageUrlRu());
    assertEquals("url/stories/en.jpg", localized.getImageUrlEn());
  }

  @Test
  void storyImageOperationsKeepOtherLocalesAndCleanUpAllKeys() {
    StoryRepository repository = mock(StoryRepository.class);
    StoryAuditWriter audit = mock(StoryAuditWriter.class);
    StoryImageStorageService storage = mock(StoryImageStorageService.class);
    Story story =
        Story.builder()
            .id(1L)
            .imageKey("stories/shared.jpg")
            .imageKeyKz("stories/kz.jpg")
            .imageKeyRu("stories/old-ru.jpg")
            .imageKeyEn("stories/en.jpg")
            .build();
    when(repository.findById(1L)).thenReturn(Optional.of(story));
    when(repository.save(any(Story.class))).thenAnswer(call -> call.getArgument(0));
    when(storage.store(file))
        .thenReturn("stories/new-shared.jpg", "stories/new-ru.jpg", "stories/newer-ru.jpg");
    StoryService service = new StoryService(repository, audit, storage, new ObjectMapper());

    service.uploadImage(1L, admin, file, http);
    assertEquals("stories/new-shared.jpg", story.getImageKey());
    assertEquals("stories/kz.jpg", story.getImageKeyKz());
    assertEquals("stories/old-ru.jpg", story.getImageKeyRu());
    assertEquals("stories/en.jpg", story.getImageKeyEn());
    verify(storage).deleteIfManaged("stories/shared.jpg");

    service.uploadImage(1L, "ru", admin, file, http);
    assertEquals("stories/new-ru.jpg", story.getImageKeyRu());
    assertEquals("stories/new-shared.jpg", story.getImageKey());
    assertEquals("stories/kz.jpg", story.getImageKeyKz());
    assertEquals("stories/en.jpg", story.getImageKeyEn());
    verify(storage).deleteIfManaged("stories/old-ru.jpg");

    service.uploadImage(1L, "ru", admin, file, http);
    verify(storage).deleteIfManaged("stories/new-ru.jpg");
    service.deleteImage(1L, "ru", admin, http);
    assertNull(story.getImageKeyRu());
    verify(storage).deleteIfManaged("stories/newer-ru.jpg");

    service.deleteImage(1L, admin, http);
    assertNull(story.getImageKey());
    assertEquals("stories/kz.jpg", story.getImageKeyKz());
    assertEquals("stories/en.jpg", story.getImageKeyEn());
    verify(storage).deleteIfManaged("stories/new-shared.jpg");

    service.delete(1L, admin, http);
    verify(storage).deleteIfManaged("stories/kz.jpg");
    verify(storage).deleteIfManaged("stories/en.jpg");

    ArgumentCaptor<ObjectNode> newState = ArgumentCaptor.forClass(ObjectNode.class);
    verify(audit, atLeastOnce())
        .writeOrSwallow(
            eq(admin),
            eq(AdminActionType.STORY_UPDATED),
            eq(1L),
            any(),
            newState.capture(),
            eq(http));
    assertTrue(
        newState.getAllValues().stream()
            .anyMatch(node -> node.has("imageKeyRu") && !node.has("imageKey")));
  }

  @Test
  void storyHardDeleteRemovesEveryDistinctKeyAndSnapshotsLocalizedFields() {
    StoryRepository repository = mock(StoryRepository.class);
    StoryAuditWriter audit = mock(StoryAuditWriter.class);
    StoryImageStorageService storage = mock(StoryImageStorageService.class);
    Story story =
        Story.builder()
            .id(1L)
            .imageKey("stories/shared.jpg")
            .imageKeyKz("stories/kz.jpg")
            .imageKeyRu("stories/shared.jpg")
            .imageKeyEn("stories/en.jpg")
            .build();
    when(repository.findById(1L)).thenReturn(Optional.of(story));
    StoryService service = new StoryService(repository, audit, storage, new ObjectMapper());

    service.delete(1L, admin, http);

    verify(storage, times(1)).deleteIfManaged("stories/shared.jpg");
    verify(storage).deleteIfManaged("stories/kz.jpg");
    verify(storage).deleteIfManaged("stories/en.jpg");
    ArgumentCaptor<ObjectNode> snapshot = ArgumentCaptor.forClass(ObjectNode.class);
    verify(audit)
        .writeOrSwallow(
            eq(admin),
            eq(AdminActionType.STORY_DELETED),
            eq(1L),
            snapshot.capture(),
            eq(null),
            eq(http));
    assertEquals("stories/shared.jpg", snapshot.getValue().get("imageKeyRu").asText());
    assertEquals("stories/kz.jpg", snapshot.getValue().get("imageKeyKz").asText());
    assertEquals("stories/en.jpg", snapshot.getValue().get("imageKeyEn").asText());
  }

  @Test
  void invalidLocaleFailsBeforeStorageOrDatabaseAccess() {
    NewsRepository newsRepository = mock(NewsRepository.class);
    NewsImageStorageService newsStorage = mock(NewsImageStorageService.class);
    NewsService newsService =
        new NewsService(
            newsRepository, mock(NewsAuditWriter.class), newsStorage, new ObjectMapper());
    StoryRepository storyRepository = mock(StoryRepository.class);
    StoryImageStorageService storyStorage = mock(StoryImageStorageService.class);
    StoryService storyService =
        new StoryService(
            storyRepository, mock(StoryAuditWriter.class), storyStorage, new ObjectMapper());

    assertThrows(
        InvalidRequestException.class, () -> newsService.uploadImage(1L, "fr", admin, file, http));
    assertThrows(
        InvalidRequestException.class, () -> newsService.deleteImage(1L, "RU", admin, http));
    assertThrows(
        InvalidRequestException.class, () -> storyService.uploadImage(1L, "fr", admin, file, http));
    assertThrows(
        InvalidRequestException.class, () -> storyService.deleteImage(1L, "RU", admin, http));
    verify(newsStorage, never()).store(any());
    verify(storyStorage, never()).store(any());
  }
}
