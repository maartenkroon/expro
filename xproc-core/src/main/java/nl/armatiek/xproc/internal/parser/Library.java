package nl.armatiek.xproc.internal.parser;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;
import nl.armatiek.xproc.internal.model.OptionDecl;
import nl.armatiek.xproc.internal.model.StepDeclaration;

/** An imported pipeline document (a p:library or a p:declare-step). */
final class Library {

  final URI uri;
  final XdmNode root;
  final boolean isLibrary;
  /** Declarations in the document itself, with their visibility. */
  final Map<QName, StepDeclaration> ownTypes = new LinkedHashMap<>();
  final Map<QName, OptionDecl> ownOptions = new LinkedHashMap<>();
  final List<Library> imports = new ArrayList<>();
  TypeScope scope;
  boolean loaded;

  Library(URI uri, XdmNode root, boolean isLibrary) {
    this.uri = uri;
    this.root = root;
    this.isLibrary = isLibrary;
  }

  /** Step types visible to a document importing this library (transitively). */
  Map<QName, StepDeclaration> visibleTypes() {
    Map<QName, StepDeclaration> m = new LinkedHashMap<>();
    collectTypes(m, new HashSet<>());
    return m;
  }

  private void collectTypes(Map<QName, StepDeclaration> m, Set<Library> visited) {
    if (!visited.add(this)) {
      return;
    }
    for (Map.Entry<QName, StepDeclaration> e : ownTypes.entrySet()) {
      if (!e.getValue().privateVisibility || !isLibrary) {
        m.putIfAbsent(e.getKey(), e.getValue());
      }
    }
    if (isLibrary) {
      for (Library lib : imports) {
        lib.collectTypes(m, visited);
      }
    }
  }

  /** Static options visible to a document importing this library (transitively). */
  Map<QName, OptionDecl> visibleOptions() {
    Map<QName, OptionDecl> m = new LinkedHashMap<>();
    collectOptions(m, new HashSet<>());
    return m;
  }

  private void collectOptions(Map<QName, OptionDecl> m, Set<Library> visited) {
    if (!visited.add(this) || !isLibrary) {
      return;
    }
    for (Library lib : imports) {
      lib.collectOptions(m, visited);
    }
    for (Map.Entry<QName, OptionDecl> e : ownOptions.entrySet()) {
      if (!e.getValue().privateVisibility) {
        m.put(e.getKey(), e.getValue());
      }
    }
  }
}
