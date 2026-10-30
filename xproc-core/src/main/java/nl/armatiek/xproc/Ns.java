package nl.armatiek.xproc;

import net.sf.saxon.s9api.QName;

/**
 * Namespace URIs and frequently used names of the XProc vocabulary.
 */
public final class Ns {

  /** The XProc namespace (conventional prefix {@code p}). */
  public static final String XPROC = "http://www.w3.org/ns/xproc";
  /** The XProc step vocabulary namespace (conventional prefix {@code c}). */
  public static final String XPROC_STEP = "http://www.w3.org/ns/xproc-step";
  /** The XProc error namespace (conventional prefix {@code err}). */
  public static final String XPROC_ERROR = "http://www.w3.org/ns/xproc-error";
  public static final String XS = "http://www.w3.org/2001/XMLSchema";
  public static final String XML = "http://www.w3.org/XML/1998/namespace";
  public static final String XMLNS = "http://www.w3.org/2000/xmlns/";
  public static final String FN = "http://www.w3.org/2005/xpath-functions";
  public static final String MAP = "http://www.w3.org/2005/xpath-functions/map";
  public static final String ARRAY = "http://www.w3.org/2005/xpath-functions/array";
  public static final String MATH = "http://www.w3.org/2005/xpath-functions/math";
  public static final String XHTML = "http://www.w3.org/1999/xhtml";
  public static final String XINCLUDE = "http://www.w3.org/2001/XInclude";
  public static final String XSLT = "http://www.w3.org/1999/XSL/Transform";
  /** Namespace for implementation specific extensions of this processor. */
  public static final String EXT = "http://armatiek.nl/ns/xproc";

  public static final QName CONTENT_TYPE = new QName("content-type");
  public static final QName BASE_URI = new QName("base-uri");
  public static final QName SERIALIZATION = new QName("serialization");

  private Ns() {
  }

  public static QName p(String local) {
    return new QName("p", XPROC, local);
  }

  public static QName c(String local) {
    return new QName("c", XPROC_STEP, local);
  }

  public static QName err(String local) {
    return new QName("err", XPROC_ERROR, local);
  }

  public static QName xml(String local) {
    return new QName("xml", XML, local);
  }
}
