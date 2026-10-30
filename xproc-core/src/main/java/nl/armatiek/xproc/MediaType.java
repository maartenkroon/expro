package nl.armatiek.xproc;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An (immutable) media type of the form {@code type/subtype+suffix; param=value}.
 */
public final class MediaType {

  /** The five kinds of documents distinguished by XProc. */
  public enum Kind {
    XML, HTML, TEXT, JSON, OTHER
  }

  public static final MediaType XML = parse("application/xml");
  public static final MediaType HTML = parse("text/html");
  public static final MediaType XHTML = parse("application/xhtml+xml");
  public static final MediaType TEXT = parse("text/plain");
  public static final MediaType JSON = parse("application/json");
  public static final MediaType OCTET_STREAM = parse("application/octet-stream");
  public static final MediaType ZIP = parse("application/zip");

  private final String type;
  private final String subtype;
  private final String suffix;
  private final Map<String, String> parameters;
  private final boolean forbidden;

  private MediaType(String type, String subtype, String suffix, Map<String, String> parameters,
      boolean forbidden) {
    this.type = type;
    this.subtype = subtype;
    this.suffix = suffix;
    this.parameters = parameters;
    this.forbidden = forbidden;
  }

  /**
   * Parses a media type. Raises {@code err:XD0079} if the value is not a valid media type.
   */
  public static MediaType parse(String value) {
    MediaType mt = tryParse(value);
    if (mt == null) {
      throw XProcException.xd(79, "Invalid media type: '" + value + "'");
    }
    return mt;
  }

  /** Parses a media type, returning null if the value is not a valid media type. */
  public static MediaType tryParse(String value) {
    if (value == null) {
      return null;
    }
    String s = value.trim();
    boolean forbidden = false;
    if (s.startsWith("-")) {
      forbidden = true;
      s = s.substring(1);
    }
    String params = null;
    int semi = s.indexOf(';');
    if (semi >= 0) {
      params = s.substring(semi + 1);
      s = s.substring(0, semi).trim();
    }
    int slash = s.indexOf('/');
    if (slash <= 0 || slash == s.length() - 1 || s.indexOf('/', slash + 1) >= 0) {
      return null;
    }
    String type = s.substring(0, slash).toLowerCase(Locale.ROOT);
    String sub = s.substring(slash + 1).toLowerCase(Locale.ROOT);
    String suffix = null;
    int plus = sub.indexOf('+');
    if (plus >= 0) {
      suffix = sub.substring(plus + 1);
      sub = sub.substring(0, plus);
      if (suffix.isEmpty() || sub.isEmpty()) {
        return null;
      }
    }
    if (!isToken(type) || !isToken(sub) || (suffix != null && !isToken(suffix))) {
      return null;
    }
    Map<String, String> map = new LinkedHashMap<>();
    if (params != null) {
      for (String p : params.split(";")) {
        p = p.trim();
        if (p.isEmpty()) {
          continue;
        }
        int eq = p.indexOf('=');
        if (eq <= 0) {
          return null;
        }
        String v = p.substring(eq + 1).trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
          v = v.substring(1, v.length() - 1);
        }
        map.put(p.substring(0, eq).trim().toLowerCase(Locale.ROOT), v);
      }
    }
    return new MediaType(type, sub, suffix, Collections.unmodifiableMap(map), forbidden);
  }

  private static boolean isToken(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c <= ' ' || c >= 127 || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) {
        return false;
      }
    }
    return true;
  }

  public String getType() {
    return type;
  }

  public String getSubtype() {
    return subtype;
  }

  public String getSuffix() {
    return suffix;
  }

  public Map<String, String> getParameters() {
    return parameters;
  }

  public String getParameter(String name) {
    return parameters.get(name.toLowerCase(Locale.ROOT));
  }

  public String getCharset() {
    return getParameter("charset");
  }

  public Charset getCharset(Charset dflt) {
    String cs = getCharset();
    if (cs == null) {
      return dflt;
    }
    try {
      return Charset.forName(cs);
    } catch (Exception e) {
      throw XProcException.xd(39, "Unsupported character set: " + cs);
    }
  }

  public boolean isForbidden() {
    return forbidden;
  }

  /** The media type without parameters. */
  public MediaType withoutParameters() {
    if (parameters.isEmpty() && !forbidden) {
      return this;
    }
    return new MediaType(type, subtype, suffix, Collections.emptyMap(), false);
  }

  public MediaType withParameter(String name, String value) {
    Map<String, String> map = new LinkedHashMap<>(parameters);
    if (value == null) {
      map.remove(name);
    } else {
      map.put(name, value);
    }
    return new MediaType(type, subtype, suffix, Collections.unmodifiableMap(map), forbidden);
  }

  public Kind getKind() {
    if (isHtml()) {
      return Kind.HTML;
    }
    if (isXml()) {
      return Kind.XML;
    }
    if (isJson()) {
      return Kind.JSON;
    }
    if (isText()) {
      return Kind.TEXT;
    }
    return Kind.OTHER;
  }

  public boolean isXml() {
    if (isHtml()) {
      return false;
    }
    return ("application".equals(type) && "xml".equals(subtype))
        || ("text".equals(type) && "xml".equals(subtype))
        || "xml".equals(suffix);
  }

  public boolean isHtml() {
    return ("text".equals(type) && "html".equals(subtype))
        || ("application".equals(type) && "xhtml".equals(subtype) && "xml".equals(suffix));
  }

  public boolean isMarkup() {
    return isXml() || isHtml();
  }

  public boolean isJson() {
    return "application".equals(type) && ("json".equals(subtype) && suffix == null
        || "json".equals(suffix));
  }

  public boolean isText() {
    if (isXml() || isHtml() || isJson()) {
      return false;
    }
    if ("text".equals(type)) {
      return true;
    }
    if (!"application".equals(type) || suffix != null) {
      return false;
    }
    return switch (subtype) {
      case "javascript", "relax-ng-compact-syntax", "xquery", "x-www-form-urlencoded",
          "ecmascript", "x-javascript", "sparql-query" -> true;
      default -> false;
    };
  }

  /**
   * True if this (pattern) media type matches the given (actual) media type. Wildcards
   * ({@code *}) are allowed for type, subtype, and suffix. Parameters are ignored.
   */
  public boolean matches(MediaType actual) {
    if (!"*".equals(type) && !type.equals(actual.type)) {
      return false;
    }
    if (suffix == null) {
      if ("*".equals(subtype)) {
        return true;
      }
      return subtype.equals(actual.subtype) && actual.suffix == null;
    }
    if (!"*".equals(suffix) && !suffix.equals(actual.suffix)) {
      return false;
    }
    if (actual.suffix == null) {
      return false;
    }
    return "*".equals(subtype) || subtype.equals(actual.subtype);
  }

  /** The type without parameters, e.g. {@code application/xml}. */
  public String toBaseString() {
    return type + "/" + subtype + (suffix == null ? "" : "+" + suffix);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    if (forbidden) {
      sb.append('-');
    }
    sb.append(toBaseString());
    for (Map.Entry<String, String> e : parameters.entrySet()) {
      sb.append("; ").append(e.getKey()).append('=').append(e.getValue());
    }
    return sb.toString();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof MediaType m && m.toString().equals(toString());
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }

  /**
   * Expands a content-types attribute value (a whitespace separated list of media types and
   * shortcuts) into a list of media types (some of which may be forbidden).
   *
   * @throws XProcException err:XS0111 for unknown shortcuts, err:XD0079 for invalid types
   */
  public static List<MediaType> parseContentTypes(String value) {
    List<MediaType> list = new ArrayList<>();
    if (value == null) {
      list.add(parse("*/*"));
      return list;
    }
    for (String token : value.trim().split("\\s+")) {
      if (token.isEmpty()) {
        continue;
      }
      boolean neg = token.startsWith("-");
      String t = neg ? token.substring(1) : token;
      if (t.indexOf('/') >= 0) {
        MediaType mt = tryParse(token);
        if (mt == null) {
          throw XProcException.xs(111, "Invalid content type: " + token);
        }
        list.add(mt);
        continue;
      }
      String[] expansion = switch (t) {
        case "xml" -> new String[] {"application/xml", "text/xml", "*/*+xml",
            "-application/xhtml+xml"};
        case "html" -> new String[] {"text/html", "application/xhtml+xml"};
        case "text" -> new String[] {"text/*", "-text/html", "-text/xml",
            "application/javascript", "application/relax-ng-compact-syntax",
            "application/xquery"};
        case "json" -> new String[] {"application/json"};
        case "any" -> new String[] {"*/*"};
        default -> throw XProcException.xs(111, "Unrecognized content type shortcut: " + token);
      };
      for (String e : expansion) {
        if (neg) {
          if (!e.startsWith("-")) {
            list.add(parse("-" + e));
          }
        } else {
          list.add(parse(e));
        }
      }
    }
    return list;
  }

  /** Tests whether a content type is acceptable according to an expanded content types list. */
  public static boolean acceptable(List<MediaType> patterns, MediaType actual) {
    Boolean result = null;
    for (MediaType p : patterns) {
      if (p.matches(actual)) {
        result = !p.isForbidden();
      }
    }
    return result != null && result;
  }
}
