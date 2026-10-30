package nl.armatiek.xproc.internal.steps;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.XPathExecutable;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.util.TreeWriter;

/**
 * Copies a document while giving subclasses the opportunity to handle nodes matching a
 * selection pattern. By default, matching any node raises err:XC0023.
 */
abstract class Rewriter {

  protected final XPathExecutable pattern;
  protected final TreeWriter tw;

  Rewriter(Processor processor, XPathExecutable pattern) {
    this.pattern = pattern;
    this.tw = new TreeWriter(processor);
  }

  XdmNode run(XdmNode doc) {
    tw.startDocument(doc.getBaseURI());
    if (matches(doc)) {
      documentMatched(doc);
    } else {
      children(doc);
    }
    return tw.endDocument();
  }

  protected boolean matches(XdmNode node) {
    return StepSupport.matches(pattern, node);
  }

  protected void children(XdmNode node) {
    for (XdmNode c : node.children()) {
      node(c);
    }
  }

  protected void node(XdmNode n) {
    if (n.getNodeKind() == XdmNodeKind.ELEMENT) {
      if (matches(n)) {
        elementMatched(n);
      } else {
        element(n);
      }
    } else if (matches(n)) {
      otherMatched(n);
    } else {
      tw.addSubtree(n);
    }
  }

  /** Copies an (unmatched) element, processing attributes and children. */
  protected void element(XdmNode e) {
    tw.addStartElement(e);
    attributes(e);
    children(e);
    tw.addEndElement();
  }

  protected void attributes(XdmNode e) {
    for (XdmNode a : StepSupport.attributes(e)) {
      if (checkAttributes() && matches(a)) {
        attributeMatched(e, a);
      } else {
        tw.addAttribute(a);
      }
    }
  }

  protected boolean checkAttributes() {
    return true;
  }

  protected void documentMatched(XdmNode doc) {
    throw notAllowed(doc);
  }

  protected void elementMatched(XdmNode e) {
    throw notAllowed(e);
  }

  protected void attributeMatched(XdmNode owner, XdmNode attr) {
    throw notAllowed(attr);
  }

  protected void otherMatched(XdmNode n) {
    throw notAllowed(n);
  }

  protected XProcException notAllowed(XdmNode n) {
    return XProcException.xc(23, "The match pattern matched a " + n.getNodeKind().toString()
        .toLowerCase() + " node, which is not allowed for this step");
  }
}
