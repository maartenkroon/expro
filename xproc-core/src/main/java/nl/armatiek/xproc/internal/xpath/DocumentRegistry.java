package nl.armatiek.xproc.internal.xpath;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

import net.sf.saxon.om.Item;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.s9api.XdmItem;
import net.sf.saxon.s9api.XdmValue;
import nl.armatiek.xproc.XProcDocument;

/**
 * Associates XDM representations with the documents (and thereby the document properties) they
 * belong to, so that p:document-properties() can find the properties of an item. Items are
 * compared by identity and held weakly.
 */
public final class DocumentRegistry {

  private static final class Key extends WeakReference<Object> {
    private final int hash;

    Key(Object referent, ReferenceQueue<Object> queue) {
      super(referent, queue);
      this.hash = System.identityHashCode(referent);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      return obj instanceof Key k && k.hash == hash && k.get() != null && k.get() == get();
    }
  }

  private final Map<Key, XProcDocument> map = new HashMap<>();
  private final ReferenceQueue<Object> queue = new ReferenceQueue<>();

  public synchronized void register(XProcDocument doc) {
    XdmValue v = doc.getValue();
    if (v.size() != 1) {
      return;
    }
    Object key = keyOf(v.itemAt(0).getUnderlyingValue());
    if (key != null) {
      expunge();
      map.put(new Key(key, queue), doc);
    }
  }

  public synchronized XProcDocument lookup(Item item) {
    Object key = keyOf(item);
    if (key == null) {
      return null;
    }
    return map.get(new Key(key, null));
  }

  public XProcDocument lookup(XdmItem item) {
    return lookup(item.getUnderlyingValue());
  }

  private void expunge() {
    java.lang.ref.Reference<?> r;
    while ((r = queue.poll()) != null) {
      map.remove(r);
    }
  }

  private static Object keyOf(Item item) {
    if (item instanceof NodeInfo node) {
      NodeInfo root = node.getRoot();
      // Only the document node itself is associated with the document.
      return node.equals(root) ? root.getTreeInfo() : null;
    }
    return item;
  }
}
