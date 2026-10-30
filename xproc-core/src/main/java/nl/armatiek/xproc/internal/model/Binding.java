package nl.armatiek.xproc.internal.model;

import java.util.List;
import java.util.Set;

import net.sf.saxon.s9api.XdmNode;

/** A single data source of a connection. */
public sealed interface Binding {

  /**
   * A connection to a readable port. The producer is either a step whose output is read, or a
   * container whose input (including "current" and "error") is read.
   */
  record Pipe(Node producer, String port) implements Binding {
  }

  /** An empty sequence of documents. */
  record Empty() implements Binding {
  }

  /**
   * An inline document (explicit p:inline or implicit inline).
   *
   * @param element the p:inline element, or the implicit inline element itself
   * @param implicit true for implicit inlines
   * @param contentType the statically determined content type (null means application/xml)
   * @param encoding the encoding attribute (or null)
   * @param documentProperties expression for the document-properties attribute (or null)
   * @param excludedNamespaces namespace URIs excluded from inline XML content
   * @param templates the compiled value templates in the content (keyed by node), null if
   *        value templates are not expanded
   * @param contextPort the default readable port for value templates referring to the context
   */
  record Inline(XdmNode element, boolean implicit, String contentType, String encoding,
      XPathExpr documentProperties, Set<String> excludedNamespaces, InlineTemplates templates,
      Pipe contextPort, ExprContext context) implements Binding {
  }

  /** A document read from a URI. */
  record Document(ValueTemplate href, String contentType, XPathExpr documentProperties,
      XPathExpr parameters, Pipe contextPort, ExprContext context) implements Binding {
  }

  /** Value templates found in inline content, indexed by node. */
  final class InlineTemplates {
    public final java.util.Map<XdmNode, ValueTemplate> byNode;
    /** Nodes (attributes, elements) carrying p:inline-expand-text which must be dropped. */
    public final Set<XdmNode> dropped;
    /** Elements excluded by use-when. */
    public final Set<XdmNode> excluded;

    public InlineTemplates(java.util.Map<XdmNode, ValueTemplate> byNode, Set<XdmNode> dropped,
        Set<XdmNode> excluded) {
      this.byNode = byNode;
      this.dropped = dropped;
      this.excluded = excluded;
    }

    public List<XPathExpr> expressions() {
      return byNode.values().stream().flatMap(t -> t.expressions().stream()).toList();
    }
  }
}
