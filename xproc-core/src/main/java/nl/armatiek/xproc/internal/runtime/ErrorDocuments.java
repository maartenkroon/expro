package nl.armatiek.xproc.internal.runtime;

import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;
import nl.armatiek.xproc.Ns;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.util.TreeWriter;

/** Builds c:errors documents for p:catch. */
public final class ErrorDocuments {

  private ErrorDocuments() {
  }

  public static XdmNode create(Processor processor, XProcException e) {
    TreeWriter tw = new TreeWriter(processor);
    tw.startDocument(null);
    tw.addStartElement(Ns.c("errors"));
    tw.addStartElement(Ns.c("error"));
    java.util.Map<String, String> used = new java.util.HashMap<>();
    used.put("c", Ns.XPROC_STEP);
    addQNameAttribute(tw, "code", e.getCode(), used);
    if (e.getCauseCode() != null) {
      addQNameAttribute(tw, "cause", e.getCauseCode(), used);
    }
    if (e.getStepName() != null) {
      tw.addAttribute(new QName("name"), e.getStepName());
    }
    if (e.getStepType() != null) {
      addQNameAttribute(tw, "type", e.getStepType(), used);
    }
    if (e.getLocation() != null) {
      tw.addAttribute(new QName("href"), e.getLocation());
    }
    if (e.getLine() > 0) {
      tw.addAttribute(new QName("line"), Integer.toString(e.getLine()));
    }
    if (e.getColumn() > 0) {
      tw.addAttribute(new QName("column"), Integer.toString(e.getColumn()));
    }
    if (e.getDetails().isEmpty()) {
      if (e.getMessage() != null) {
        tw.addText(e.getMessage());
      }
    } else {
      for (XdmNode d : e.getDetails()) {
        tw.addSubtree(d);
      }
    }
    tw.addEndElement();
    tw.addEndElement();
    return tw.endDocument();
  }

  private static void addQNameAttribute(TreeWriter tw, String name, QName value,
      java.util.Map<String, String> used) {
    String ns = value.getNamespace();
    if (ns.isEmpty()) {
      tw.addAttribute(new QName(name), value.getLocalName());
      return;
    }
    String prefix = value.getPrefix();
    if (prefix.isEmpty()) {
      prefix = Ns.XPROC_ERROR.equals(ns) ? "err" : Ns.XPROC.equals(ns) ? "p" : "ns";
    }
    String base = prefix;
    int n = 1;
    while (used.containsKey(prefix) && !used.get(prefix).equals(ns)) {
      prefix = base + n++;
    }
    used.put(prefix, ns);
    tw.addNamespace(prefix, ns);
    tw.addAttribute(new QName(name), prefix + ":" + value.getLocalName());
  }
}
