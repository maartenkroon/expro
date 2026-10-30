package nl.armatiek.xproc.internal.model;

import java.util.Map;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SequenceType;
import net.sf.saxon.s9api.XdmValue;

/** The declaration of an option (p:option). */
public final class OptionDecl implements Binder {

  public final QName name;
  public String asText;
  public SequenceType as;
  /** The allowed values (from the values attribute) or null. */
  public XdmValue values;
  public boolean isStatic;
  public boolean required;
  public XPathExpr select;
  public boolean privateVisibility;
  /** The e:type annotation (XPathExpression, XSLTSelectionPattern, ...) if any. */
  public String specialType;
  /** True if relative xs:anyURI values must not be made absolute (p:namespace-rename). */
  public boolean noResolve;
  public Location location = Location.UNKNOWN;
  public ExprContext context;

  /** The value of a static option (computed during static analysis). */
  public XdmValue staticValue;
  public Map<String, String> staticNamespaces;

  public OptionDecl(QName name) {
    this.name = name;
  }

  @Override
  public QName getName() {
    return name;
  }

  @Override
  public boolean isStatic() {
    return isStatic;
  }

  @Override
  public XdmValue getStaticValue() {
    return staticValue;
  }

  @Override
  public Map<String, String> getStaticNamespaces() {
    return staticNamespaces;
  }

  /** True if the declared type is a map or array type ("map attribute"). */
  public boolean isMapOrArrayType() {
    if (asText == null) {
      return false;
    }
    String t = asText.trim();
    return t.startsWith("map(") || t.startsWith("array(") || t.startsWith("map ")
        || t.startsWith("array ");
  }

  @Override
  public String toString() {
    return "option " + name;
  }
}
