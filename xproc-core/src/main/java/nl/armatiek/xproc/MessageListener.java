package nl.armatiek.xproc;

/**
 * Receives messages produced while running a pipeline: values of [p:]message attributes, the
 * p:message step, xsl:message / fn:message output and warnings.
 */
@FunctionalInterface
public interface MessageListener {

  enum Level {
    DEBUG, INFO, WARNING, ERROR
  }

  /**
   * @param level the severity
   * @param stepName the name of the step producing the message (may be null)
   * @param message the message text
   */
  void message(Level level, String stepName, String message);

  /** A listener that writes messages to standard error. */
  MessageListener STDERR = (level, step, msg) -> System.err.println(
      (level == Level.INFO ? "" : level + ": ") + msg);

  /** A listener that ignores all messages. */
  MessageListener SILENT = (level, step, msg) -> {
  };
}
