package kz.hrms.splitupauth.branding;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MojibakeScanTest {

  private static final Pattern OBVIOUS_MOJIBAKE =
      Pattern.compile("Р’|РЎ|РЃ|РЋ|Рџ|Рќ|Рњ|Рђ|РЅ|Р°|Рµ|Рё|Рѕ|Р»|СЃ|СЂ|С‹|С‚|вЂ");

  private static final Set<String> TEXT_EXTENSIONS =
      Set.of(".java", ".json", ".md", ".properties", ".sql", ".txt", ".xml", ".yml", ".yaml");

  @Test
  void productionTextDoesNotContainObviousMojibake() throws IOException {
    List<Path> roots = List.of(Path.of("src/main/java"), Path.of("src/main/resources"));
    List<String> failures = new ArrayList<>();

    for (Path root : roots) {
      if (!Files.exists(root)) continue;
      try (Stream<Path> paths = Files.walk(root)) {
        paths.filter(Files::isRegularFile).filter(MojibakeScanTest::isTextFile).forEach(path -> scan(path, failures));
      }
    }

    if (!failures.isEmpty()) {
      fail("Obvious mojibake found:%n%s".formatted(String.join("%n", failures)));
    }
  }

  private static boolean isTextFile(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot));
  }

  private static void scan(Path path, List<String> failures) {
    try {
      List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        if (OBVIOUS_MOJIBAKE.matcher(lines.get(i)).find()) {
          failures.add(path + ":" + (i + 1));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unable to scan " + path, e);
    }
  }
}
