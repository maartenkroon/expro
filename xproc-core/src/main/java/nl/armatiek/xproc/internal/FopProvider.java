package nl.armatiek.xproc.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.fop.apps.FopConfParser;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.FopFactoryBuilder;
import org.apache.fop.apps.io.ResourceResolverFactory;
import org.apache.xmlgraphics.io.Resource;
import org.apache.xmlgraphics.io.ResourceResolver;
import org.xml.sax.SAXException;

import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.io.DocumentLoader;

/**
 * The Apache FOP factory of a processor, used by p:xsl-formatter. It is created on first use,
 * from the fop.xconf file of the configuration if there is one, and shared by all pipeline runs
 * (a FopFactory is thread-safe). FOP reads images, fonts and other resources through the
 * document loader, so the URI mapper and the file and network access settings apply to them.
 */
public final class FopProvider {

  private final ProcessorContext pc;
  private volatile FopFactory factory;

  FopProvider(ProcessorContext pc) {
    this.pc = pc;
  }

  /** The factory; raises err:XC0167 if the FOP configuration cannot be read. */
  public FopFactory factory() {
    FopFactory f = factory;
    if (f == null) {
      synchronized (this) {
        f = factory;
        if (f == null) {
          f = create();
          factory = f;
        }
      }
    }
    return f;
  }

  /** Drops the factory, so the next use reads the FOP configuration again. */
  public synchronized void clear() {
    factory = null;
  }

  private FopFactory create() {
    URI conf = pc.config.getFopConfiguration();
    ResourceResolver resolver = resolver();
    if (conf == null) {
      return new FopFactoryBuilder(Path.of("").toAbsolutePath().toUri(), resolver).build();
    }
    if ("file".equalsIgnoreCase(conf.getScheme()) && !Files.isRegularFile(Path.of(conf))) {
      throw XProcException.xc(167, "The FOP configuration file " + conf + " does not exist");
    }
    try (InputStream in = open(conf)) {
      // Relative URIs in the configuration are relative to its location, as with FOP itself
      return new FopConfParser(in, conf.resolve("."), resolver).getFopFactoryBuilder().build();
    } catch (IOException | SAXException | RuntimeException e) {
      throw XProcException.xc(167, "Cannot read the FOP configuration " + conf + ": "
          + e.getMessage(), e);
    }
  }

  private static InputStream open(URI conf) throws IOException {
    if ("file".equalsIgnoreCase(conf.getScheme())) {
      return Files.newInputStream(Path.of(conf));
    }
    return conf.toURL().openStream();
  }

  /** Our resolver, with FOP's own handling of its temporary (tmp:) resources. */
  private ResourceResolver resolver() {
    var builder = ResourceResolverFactory.createSchemeAwareResourceResolverBuilder(
        new LoaderResolver(pc));
    builder.registerResourceResolverForScheme("tmp",
        ResourceResolverFactory.createDefaultResourceResolver());
    return builder.build();
  }

  /** Reads resources with the document loader and writes only to permitted file: URIs. */
  private record LoaderResolver(ProcessorContext pc) implements ResourceResolver {

    @Override
    public Resource getResource(URI uri) throws IOException {
      try {
        DocumentLoader.Resource r = pc.loader.read(uri);
        return new Resource(r.reportedContentType(), new ByteArrayInputStream(r.bytes()));
      } catch (XProcException e) {
        if ("XD0021".equals(e.getCode().getLocalName())) {
          // FOP itself only reports that the resource was not found
          pc.messages().message(MessageListener.Level.WARNING, null, e.getMessage());
        }
        throw new IOException(e.getMessage(), e);
      }
    }

    @Override
    public OutputStream getOutputStream(URI uri) throws IOException {
      URI mapped = pc.mapUri(uri);
      try {
        pc.checkAccess(mapped);
      } catch (XProcException e) {
        throw new IOException(e.getMessage(), e);
      }
      if (!"file".equalsIgnoreCase(mapped.getScheme())) {
        throw new IOException("Cannot write to " + uri);
      }
      return Files.newOutputStream(Path.of(mapped));
    }
  }
}
