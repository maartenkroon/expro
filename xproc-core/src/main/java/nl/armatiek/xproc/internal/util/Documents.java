package nl.armatiek.xproc.internal.util;

import java.net.URI;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.XdmArray;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmFunctionItem;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmMap;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;

/** Creating documents from XDM items (XProc 3.1, section 3.3). */
public final class Documents {

  private Documents() {
  }

  /**
   * Converts an item into a document.
   *
   * @param source the document the item was selected from (may be null)
   * @param error the error to raise for attribute, namespace and function items
   */
  public static XProcDocument fromItem(Processor processor, XdmItem item, XProcDocument source,
      XProcException error) {
    if (item instanceof XdmNode n) {
      switch (n.getNodeKind()) {
        case ATTRIBUTE, NAMESPACE -> throw error;
        case DOCUMENT -> {
          if (source != null && n.equals(source.getNode())) {
            return source;
          }
          return XProcDocument.of(processor, n, MediaType.XML, n.getBaseURI());
        }
        default -> {
          URI base = n.getBaseURI();
          TreeWriter tw = new TreeWriter(processor);
          tw.startDocument(base);
          net.sf.saxon.s9api.QName xmlBase = new net.sf.saxon.s9api.QName("xml",
              nl.armatiek.xproc.Ns.XML, "base");
          if (n.getNodeKind() == XdmNodeKind.ELEMENT && n.getAttributeValue(xmlBase) != null
              && base != null) {
            // make a relative xml:base absolute, it is relative to the old parent
            tw.addStartElement(n);
            for (XdmNode a : n.axisIterator(net.sf.saxon.s9api.Axis.ATTRIBUTE).stream()
                .toList()) {
              if (a.getNodeName().equals(xmlBase)) {
                tw.addAttribute(xmlBase, base.toString());
              } else {
                tw.addAttribute(a);
              }
            }
            tw.addChildren(n);
            tw.addEndElement();
          } else {
            tw.addSubtree(n);
          }
          return XProcDocument.of(processor, tw.endDocument(),
              n.getNodeKind() == XdmNodeKind.TEXT ? MediaType.TEXT : MediaType.XML, base);
        }
      }
    }
    if (item instanceof XdmMap || item instanceof XdmArray || item instanceof XdmAtomicValue) {
      return XProcDocument.of(processor, item, MediaType.JSON, (URI) null);
    }
    if (item instanceof XdmFunctionItem) {
      throw error;
    }
    return XProcDocument.of(processor, item, MediaType.JSON, (URI) null);
  }
}
