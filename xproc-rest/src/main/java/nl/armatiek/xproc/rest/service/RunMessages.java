package nl.armatiek.xproc.rest.service;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.rest.model.Message;

/**
 * Collects the messages of the pipeline run on the current thread. The processor is shared by
 * all requests, so its message listener dispatches to the run that is in scope.
 */
public final class RunMessages {

  private static final ScopedValue<List<Message>> CURRENT = ScopedValue.newInstance();

  private RunMessages() {
  }

  /** Runs the action, collecting its messages in the given list. */
  public static <T> T collect(List<Message> messages, Supplier<T> action) {
    return ScopedValue.where(CURRENT, Collections.synchronizedList(messages)).call(action::get);
  }

  /** The message listener of the processor. Messages outside a run are dropped. */
  public static void message(MessageListener.Level level, String stepName, String text) {
    if (CURRENT.isBound()) {
      CURRENT.get().add(new Message(Message.LevelEnum.valueOf(level.name()), text)
          .step(stepName));
    }
  }
}
