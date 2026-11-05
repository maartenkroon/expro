package nl.armatiek.xproc.internal;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.s9api.XsltExecutable;

/**
 * Compiled XSLT stylesheets kept for reuse, least recently used first out. A stylesheet is
 * identified by a digest of its serialized content, its base URI and the values that influence
 * its compilation, so a changed stylesheet is compiled again. Compiled stylesheets are immutable
 * and can be shared by concurrent pipeline runs. Thread-safe.
 */
public final class XsltCache {

  private final int maxSize;
  private final Map<String, XsltExecutable> entries;

  XsltCache(int maxSize) {
    this.maxSize = maxSize;
    this.entries = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, XsltExecutable> eldest) {
        return size() > XsltCache.this.maxSize;
      }
    };
  }

  /** A compiler of a stylesheet, called on a cache miss. */
  @FunctionalInterface
  public interface Compiler {
    XsltExecutable compile();
  }

  /**
   * Returns the cached compilation of a stylesheet, compiling and caching it on a miss.
   *
   * @param kind distinguishes compilations of different origin (for example a Schematron schema
   *        and its compiled stylesheet)
   * @param stylesheet the stylesheet (or the schema it is compiled from)
   * @param params the values that influence the compilation (static parameters)
   */
  public XsltExecutable get(Processor processor, String kind, XdmNode stylesheet,
      Map<QName, XdmValue> params, Compiler compiler) {
    String key = maxSize == 0 ? null : key(processor, kind, stylesheet, params);
    if (key == null) {
      return compiler.compile();
    }
    XsltExecutable exec;
    synchronized (this) {
      exec = entries.get(key);
    }
    if (exec == null) {
      // Compiled outside the lock: concurrent misses may compile twice, but never block others
      exec = compiler.compile();
      synchronized (this) {
        entries.put(key, exec);
      }
    }
    return exec;
  }

  public synchronized void clear() {
    entries.clear();
  }

  public synchronized int size() {
    return entries.size();
  }

  /** The cache key, or null if the parameters cannot be part of a key (maps, nodes, ...). */
  private static String key(Processor processor, String kind, XdmNode stylesheet,
      Map<QName, XdmValue> params) {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
    update(md, kind);
    update(md, String.valueOf(stylesheet.getBaseURI()));
    var sorted = params.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getKey().getEQName())).toList();
    for (Map.Entry<QName, XdmValue> e : sorted) {
      update(md, e.getKey().getEQName());
      for (XdmItem item : e.getValue()) {
        if (!(item instanceof XdmAtomicValue a)) {
          return null;
        }
        update(md, a.getPrimitiveTypeName().getEQName());
        update(md, a.getStringValue());
      }
      update(md, "");
    }
    Serializer ser = processor.newSerializer(new OutputStream() {
      @Override
      public void write(int b) {
        md.update((byte) b);
      }

      @Override
      public void write(byte[] b, int off, int len) {
        md.update(b, off, len);
      }
    });
    ser.setOutputProperty(Serializer.Property.METHOD, "xml");
    ser.setOutputProperty(Serializer.Property.INDENT, "no");
    try {
      ser.serializeNode(stylesheet);
      ser.close();
    } catch (SaxonApiException e) {
      return null;
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static void update(MessageDigest md, String s) {
    md.update(s.getBytes(StandardCharsets.UTF_8));
    md.update((byte) 0);
  }
}
