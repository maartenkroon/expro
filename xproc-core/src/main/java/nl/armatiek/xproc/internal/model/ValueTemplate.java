package nl.armatiek.xproc.internal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nl.armatiek.xproc.XProcException;

/**
 * A value template (attribute or text value template): an alternating sequence of literal
 * strings and expressions.
 */
public final class ValueTemplate {

  /** Each part is either a {@link String} or an {@link XPathExpr}. */
  public final List<Object> parts;

  private ValueTemplate(List<Object> parts) {
    this.parts = Collections.unmodifiableList(parts);
  }

  public static ValueTemplate literal(String s) {
    List<Object> p = new ArrayList<>();
    p.add(s);
    return new ValueTemplate(p);
  }

  public boolean isLiteral() {
    for (Object o : parts) {
      if (o instanceof XPathExpr) {
        return false;
      }
    }
    return true;
  }

  public String literalValue() {
    StringBuilder sb = new StringBuilder();
    for (Object o : parts) {
      sb.append(o);
    }
    return sb.toString();
  }

  public boolean usesContext() {
    for (Object o : parts) {
      if (o instanceof XPathExpr x && x.usesContext) {
        return true;
      }
    }
    return false;
  }

  public List<XPathExpr> expressions() {
    List<XPathExpr> list = new ArrayList<>();
    for (Object o : parts) {
      if (o instanceof XPathExpr x) {
        list.add(x);
      }
    }
    return list;
  }

  /**
   * Splits a value template into literal and expression parts.
   *
   * @throws XProcException err:XS0066 if the template is malformed
   */
  public static ValueTemplate parse(String value, ExprContext ctx) {
    List<Object> parts = new ArrayList<>();
    StringBuilder lit = new StringBuilder();
    int i = 0;
    int n = value.length();
    while (i < n) {
      char c = value.charAt(i);
      if (c == '{') {
        if (i + 1 < n && value.charAt(i + 1) == '{') {
          lit.append('{');
          i += 2;
          continue;
        }
        int end = findExpressionEnd(value, i + 1);
        if (end < 0) {
          throw XProcException.xs(66, "Unterminated expression in value template: " + value);
        }
        if (!lit.isEmpty()) {
          parts.add(lit.toString());
          lit.setLength(0);
        }
        String expr = value.substring(i + 1, end);
        if (!expr.isBlank()) {
          parts.add(new XPathExpr(expr, ctx));
        }
        i = end + 1;
      } else if (c == '}') {
        if (i + 1 < n && value.charAt(i + 1) == '}') {
          lit.append('}');
          i += 2;
          continue;
        }
        throw XProcException.xs(66, "Unescaped '}' in value template: " + value);
      } else {
        lit.append(c);
        i++;
      }
    }
    if (!lit.isEmpty() || parts.isEmpty()) {
      parts.add(lit.toString());
    }
    return new ValueTemplate(parts);
  }

  /** Finds the matching '}' for an expression starting at 'start', honoring strings/comments. */
  private static int findExpressionEnd(String s, int start) {
    int depth = 0;
    int i = start;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);
      if (c == '"' || c == '\'') {
        int close = s.indexOf(c, i + 1);
        while (close >= 0 && close + 1 < n && s.charAt(close + 1) == c) {
          close = s.indexOf(c, close + 2);
        }
        if (close < 0) {
          return -1;
        }
        i = close + 1;
        continue;
      }
      if (c == '(' && i + 1 < n && s.charAt(i + 1) == ':') {
        int cdepth = 1;
        i += 2;
        while (i < n && cdepth > 0) {
          if (s.startsWith("(:", i)) {
            cdepth++;
            i += 2;
          } else if (s.startsWith(":)", i)) {
            cdepth--;
            i += 2;
          } else {
            i++;
          }
        }
        continue;
      }
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        if (depth == 0) {
          return i;
        }
        depth--;
      }
      i++;
    }
    return -1;
  }
}
