package nl.armatiek.xproc.openapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.openapi.web.ApiException;

/**
 * Compiled pipelines by file. A pipeline is compiled on first use and compiled again when its
 * file changes (if enabled) or after {@link #clear()}. Compiled pipelines are immutable and run
 * concurrently. A pipeline is compiled at most once at a time; requests for other pipelines are
 * not held up by a compilation. Thread-safe.
 */
@Component
public class PipelineCache {

  private static final Logger LOG = LoggerFactory.getLogger(PipelineCache.class);

  private record Entry(XProcPipeline pipeline, FileTime modified) {
  }

  private final XProcProcessor processor;
  private final OpenApiProperties props;
  private final Map<Path, Entry> entries = new ConcurrentHashMap<>();

  public PipelineCache(XProcProcessor processor, OpenApiProperties props) {
    this.processor = processor;
    this.props = props;
  }

  /**
   * The compiled pipeline of a file.
   *
   * @throws ApiException 501 if the file does not exist
   * @throws nl.armatiek.xproc.XProcException if the pipeline cannot be compiled
   */
  public XProcPipeline get(Path file) {
    if (!Files.isRegularFile(file)) {
      entries.remove(file);
      throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "The pipeline " + file.getFileName()
          + " of this operation does not exist");
    }
    Entry entry = entries.get(file);
    if (entry != null && (!props.checkModified() || entry.modified().equals(modified(file)))) {
      return entry.pipeline();
    }
    // compute() runs at most one compilation per file at a time
    return entries.compute(file, (f, old) -> {
      FileTime modified = modified(f);
      if (old != null && (!props.checkModified() || old.modified().equals(modified))) {
        return old;
      }
      LOG.info("Compiling pipeline {}", f);
      return new Entry(processor.compile(f.toFile()), modified);
    }).pipeline();
  }

  public void clear() {
    entries.clear();
  }

  public int size() {
    return entries.size();
  }

  private static FileTime modified(Path file) {
    try {
      return Files.getLastModifiedTime(file);
    } catch (IOException e) {
      return FileTime.fromMillis(0);
    }
  }
}
