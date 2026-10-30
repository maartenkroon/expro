package nl.armatiek.xproc.internal.steps;

import java.net.URI;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.internal.ProcessorContext;

/** What an external step implementation sees of its invocation. */
public interface StepContext {

  ProcessorContext pc();

  default Processor processor() {
    return pc().processor;
  }

  String stepName();

  QName stepType();

  /** The base URI of the step element. */
  URI baseUri();

  /** The documents on an input port. */
  List<XProcDocument> inputs(String port);

  /** The single document on an input port (the port is not a sequence port). */
  default XProcDocument input(String port) {
    List<XProcDocument> docs = inputs(port);
    return docs.isEmpty() ? null : docs.get(0);
  }

  /** The value of an option (the empty sequence if it has no value). */
  XdmValue option(QName name);

  default XdmValue option(String name) {
    return option(new QName(name));
  }

  /** The namespace bindings associated with an option value. */
  Map<String, String> optionNamespaces(QName name);

  default Map<String, String> optionNamespaces(String name) {
    return optionNamespaces(new QName(name));
  }

  /** Writes a document to an output port. */
  void output(String port, XProcDocument doc);

  MessageListener messages();
}
