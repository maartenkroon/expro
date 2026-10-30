package nl.armatiek.xproc;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmValue;

/**
 * Configuration of an {@link XProcProcessor}. Configure before creating the processor; the
 * processor takes a snapshot of the settings.
 */
public final class XProcConfiguration {

  private Processor saxonProcessor;
  private MessageListener messageListener = MessageListener.STDERR;
  private UnaryOperator<URI> uriMapper;
  private final Map<QName, XdmValue> staticOptions = new LinkedHashMap<>();
  private boolean allowFileAccess = true;
  private boolean allowNetworkAccess = true;
  private String locale = java.util.Locale.getDefault().toLanguageTag();

  public XProcConfiguration() {
  }

  /**
   * Uses an existing Saxon processor. If none is set a new (non schema-aware) Saxon processor
   * is created. The XProc extension functions are registered on the processor.
   */
  public XProcConfiguration setSaxonProcessor(Processor processor) {
    this.saxonProcessor = processor;
    return this;
  }

  public Processor getSaxonProcessor() {
    return saxonProcessor;
  }

  public XProcConfiguration setMessageListener(MessageListener listener) {
    this.messageListener = listener == null ? MessageListener.SILENT : listener;
    return this;
  }

  public MessageListener getMessageListener() {
    return messageListener;
  }

  /**
   * Sets a URI mapper that is applied to every URI before it is dereferenced (a simple URI
   * resolver). Its mapping is also reported by p:lookup-uri().
   */
  public XProcConfiguration setUriMapper(UnaryOperator<URI> mapper) {
    this.uriMapper = mapper;
    return this;
  }

  public UnaryOperator<URI> getUriMapper() {
    return uriMapper;
  }

  /**
   * Provides a default value for a static option. The value is used for every static option
   * with this name in all pipelines compiled by the processor.
   */
  public XProcConfiguration setStaticOption(QName name, XdmValue value) {
    staticOptions.put(name, value);
    return this;
  }

  public Map<QName, XdmValue> getStaticOptions() {
    return staticOptions;
  }

  /** Allows or forbids access to file: URIs (err:XD0021 if forbidden). Default: allowed. */
  public XProcConfiguration setAllowFileAccess(boolean allow) {
    this.allowFileAccess = allow;
    return this;
  }

  public boolean isAllowFileAccess() {
    return allowFileAccess;
  }

  /** Allows or forbids network access (err:XD0021 if forbidden). Default: allowed. */
  public XProcConfiguration setAllowNetworkAccess(boolean allow) {
    this.allowNetworkAccess = allow;
    return this;
  }

  public boolean isAllowNetworkAccess() {
    return allowNetworkAccess;
  }

  /** The value of the p:locale system property. */
  public XProcConfiguration setLocale(String locale) {
    this.locale = locale;
    return this;
  }

  public String getLocale() {
    return locale;
  }

  XProcConfiguration copy() {
    XProcConfiguration c = new XProcConfiguration();
    c.saxonProcessor = saxonProcessor;
    c.messageListener = messageListener;
    c.uriMapper = uriMapper;
    c.staticOptions.putAll(staticOptions);
    c.allowFileAccess = allowFileAccess;
    c.allowNetworkAccess = allowNetworkAccess;
    c.locale = locale;
    return c;
  }
}
