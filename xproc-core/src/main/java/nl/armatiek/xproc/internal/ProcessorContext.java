package nl.armatiek.xproc.internal;

import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.lib.Resource;
import net.sf.saxon.lib.ResourceCollection;
import net.sf.saxon.om.Item;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmItem;
import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.XProcConfiguration;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentLoader;
import nl.armatiek.xproc.internal.io.DocumentSerializer;
import nl.armatiek.xproc.internal.model.StepDeclaration;
import nl.armatiek.xproc.internal.steps.StepRegistry;
import nl.armatiek.xproc.internal.xpath.DocumentRegistry;
import nl.armatiek.xproc.internal.xpath.EvalState;
import nl.armatiek.xproc.internal.xpath.TypeConverter;
import nl.armatiek.xproc.internal.xpath.Urify;
import nl.armatiek.xproc.internal.xpath.XPathEngine;
import nl.armatiek.xproc.internal.xpath.XProcFunctions;

/** Shared services of an XProc processor instance. */
public final class ProcessorContext {

  public static final String PRODUCT_NAME = "Armatiek XProc";
  public static final String PRODUCT_VERSION;
  public static final String SAXON_VERSION;
  public static final String VENDOR = "Armatiek";
  public static final String VENDOR_URI = "https://www.armatiek.nl";

  static {
    Properties props = new Properties();
    try (InputStream in = ProcessorContext.class.getResourceAsStream(
        "/nl/armatiek/xproc/version.properties")) {
      if (in != null) {
        props.load(in);
      }
    } catch (Exception e) {
      // ignore
    }
    PRODUCT_VERSION = props.getProperty("version", "dev");
    SAXON_VERSION = props.getProperty("saxon.version", "?");
  }

  /** The URI used for the default collection. */
  public static final String DEFAULT_COLLECTION = "urn:x-xproc:default-collection";

  public final Processor processor;
  public final XProcConfiguration config;
  public final XPathEngine xpath;
  public final TypeConverter types;
  public final DocumentRegistry registry = new DocumentRegistry();
  public final DocumentLoader loader;
  public final DocumentSerializer serializer;
  public final Urify urify = new Urify();
  public final String episode;
  public final StepRegistry steps;
  /** Declarations of the standard steps (from the step library). */
  public final Map<QName, StepDeclaration> standardSteps = new LinkedHashMap<>();

  public ProcessorContext(XProcConfiguration config) {
    this.config = config;
    this.processor = config.getSaxonProcessor() != null ? config.getSaxonProcessor()
        : new Processor(false);
    this.xpath = new XPathEngine(processor);
    this.types = new TypeConverter(xpath);
    this.loader = new DocumentLoader(this);
    this.serializer = new DocumentSerializer(processor);
    this.episode = "E" + UUID.randomUUID().toString().replace("-", "");
    this.steps = new StepRegistry();
    XProcFunctions.register(this);
    installCollectionFinder();
  }

  private void installCollectionFinder() {
    var conf = processor.getUnderlyingConfiguration();
    var previous = conf.getCollectionFinder();
    conf.setDefaultCollection(DEFAULT_COLLECTION);
    conf.setCollectionFinder((XPathContext context, String uri) -> {
      EvalState st = EvalState.current();
      List<XdmItem> found = st != null && st.collection != null ? st.collection
          : EvalState.STEP_COLLECTION.get();
      if (uri == null || DEFAULT_COLLECTION.equals(uri)) {
        if (found == null) {
          throw new net.sf.saxon.trans.XPathException("No default collection is available",
              "FODC0002");
        }
        List<XdmItem> items = found;
        return new ResourceCollection() {
          @Override
          public String getCollectionURI() {
            return null;
          }

          @Override
          public java.util.Iterator<String> getResourceURIs(XPathContext c) {
            return java.util.Collections.emptyIterator();
          }

          @Override
          public java.util.Iterator<? extends Resource> getResources(XPathContext c) {
            return items.stream().map(i -> (Resource) new Resource() {
              @Override
              public String getResourceURI() {
                return null;
              }

              @Override
              public Item getItem() {
                return i.getUnderlyingValue();
              }

              @Override
              public String getContentType() {
                return null;
              }
            }).iterator();
          }

          @Override
          public boolean isStable(XPathContext c) {
            return true;
          }
        };
      }
      return previous.findCollection(context, uri);
    });
  }

  public MessageListener messages() {
    return config.getMessageListener();
  }

  /** Applies the configured URI mapper. */
  public URI mapUri(URI uri) {
    if (config.getUriMapper() == null) {
      return uri;
    }
    URI mapped = config.getUriMapper().apply(uri);
    return mapped == null ? uri : mapped;
  }

  /** Raises err:XD0021 if access to the URI is forbidden by the configuration. */
  public void checkAccess(URI uri) {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if ("file".equals(scheme) && !config.isAllowFileAccess()) {
      throw XProcException.xd(21, "Access to file resources is not allowed: " + uri);
    }
    if (("http".equals(scheme) || "https".equals(scheme)) && !config.isAllowNetworkAccess()) {
      throw XProcException.xd(21, "Network access is not allowed: " + uri);
    }
  }
}
