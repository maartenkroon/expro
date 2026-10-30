package nl.armatiek.xproc.internal.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.saxon.s9api.QName;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.internal.model.StepDeclaration;
import nl.armatiek.xproc.internal.util.QNames;

/** The step types in scope at some point in a pipeline document. */
final class TypeScope {

  final TypeScope parent;
  final Map<QName, StepDeclaration> local = new LinkedHashMap<>();
  final List<Library> imported = new ArrayList<>();
  /** Declarations that are not registered yet (later siblings): availability suppliers. */
  final Map<QName, java.util.function.BooleanSupplier> forward = new LinkedHashMap<>();
  private final Map<QName, StepDeclaration> standard;

  TypeScope(TypeScope parent, Map<QName, StepDeclaration> standard) {
    this.parent = parent;
    this.standard = standard;
  }

  void declare(StepDeclaration decl) {
    StepDeclaration existing = lookupHere(decl.type);
    if (standard.containsKey(decl.type) || (existing != null && existing != decl)) {
      throw XProcException.xs(36, "Step type " + QNames.display(decl.type)
          + " is declared more than once");
    }
    local.put(decl.type, decl);
  }

  /** Lookup in this scope only (local declarations and imports). */
  StepDeclaration lookupHere(QName type) {
    StepDeclaration d = local.get(type);
    if (d != null) {
      return d;
    }
    for (Library lib : imported) {
      d = lib.visibleTypes().get(type);
      if (d != null) {
        return d;
      }
    }
    return null;
  }

  StepDeclaration lookup(QName type) {
    for (TypeScope s = this; s != null; s = s.parent) {
      StepDeclaration d = s.lookupHere(type);
      if (d != null) {
        return d;
      }
    }
    return standard.get(type);
  }

  /** Checks for conflicting declarations between imports (err:XS0036). */
  void checkConflicts() {
    Map<QName, StepDeclaration> seen = new LinkedHashMap<>(local);
    for (Library lib : imported) {
      for (Map.Entry<QName, StepDeclaration> e : lib.visibleTypes().entrySet()) {
        StepDeclaration prev = seen.put(e.getKey(), e.getValue());
        if (prev != null && prev != e.getValue()) {
          throw XProcException.xs(36, "Step type " + QNames.display(e.getKey())
              + " is declared more than once");
        }
      }
    }
  }
}
