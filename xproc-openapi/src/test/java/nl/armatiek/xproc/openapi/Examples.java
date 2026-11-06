package nl.armatiek.xproc.openapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Copies an example configuration directory, so the tests don't change the original. */
final class Examples {

  private Examples() {
  }

  /** Copies examples/{name} (with its subdirectories) into a directory. */
  static void copy(String name, Path target) throws IOException {
    Path source = Path.of("examples", name);
    try (Stream<Path> files = Files.walk(source)) {
      for (Path f : files.toList()) {
        Path t = target.resolve(source.relativize(f).toString());
        try {
          if (Files.isDirectory(f)) {
            Files.createDirectories(t);
          } else {
            Files.copy(f, t);
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
  }
}
