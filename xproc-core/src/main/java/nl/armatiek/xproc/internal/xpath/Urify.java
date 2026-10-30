package nl.armatiek.xproc.internal.xpath;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nl.armatiek.xproc.XProcException;

/** Implementation of the p:urify() heuristics (XProc 3.1, section 8.9). */
public final class Urify {

  private static final Pattern WIN_DRIVE = Pattern.compile("^(file:/*)?([a-zA-Z]):(.*)$",
      Pattern.DOTALL);
  private static final Pattern FILE_AUTH = Pattern.compile("^file://([^/]+)(/.*)?$",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern FILE_ANY = Pattern.compile("^file:(.*)$",
      Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$",
      Pattern.DOTALL);
  private static final Pattern AUTH = Pattern.compile("^//([^/]+)(/.*)?$", Pattern.DOTALL);

  private final boolean windows;
  private final String separator;

  public Urify() {
    this(System.getProperty("os.name", "").startsWith("Windows"), File.separator);
  }

  public Urify(boolean windows, String separator) {
    this.windows = windows;
    this.separator = separator;
  }

  private static final class Features {
    String scheme;
    boolean explicitScheme;
    boolean hierarchical = true;
    String authority;
    String drive;
    String path = "";
    boolean absolute;
    String suffix = "";
  }

  public String urify(String filepath, String basedir) {
    String fp = filepath;
    if (!"/".equals(separator) && willBeFileUri(fp, basedir)) {
      fp = fp.replace(separator, "/");
    }
    Features f = analyze(fp);
    if (!f.hierarchical) {
      return filepath;
    }
    splitSuffix(f);
    if (f.scheme != null && f.absolute) {
      if (!f.explicitScheme) {
        f.path = fixup(f.path);
      }
      return construct(f);
    }

    String base = basedir;
    if (base == null) {
      String cwd = System.getProperty("user.dir");
      if (cwd == null) {
        base = "";
      } else {
        if (!cwd.endsWith(File.separator)) {
          cwd = cwd + File.separator;
        }
        base = cwd;
      }
      if (!"/".equals(separator)) {
        base = base.replace(separator, "/");
      }
    } else if (!"/".equals(separator) && willBeFileUri(base, null)) {
      base = base.replace(separator, "/");
    }
    Features b = analyze(base);
    if (!b.hierarchical) {
      throw XProcException.xd(80, "Base URI has a non-hierarchical scheme: " + basedir);
    }
    boolean baseImplicit = b.scheme == null || !b.explicitScheme;
    if (b.scheme == null) {
      b.scheme = "file";
    }
    if (!b.absolute) {
      throw XProcException.xd(74, "No absolute base URI available to make '" + filepath
          + "' absolute");
    }
    if (f.drive != null && (b.drive == null || !b.drive.equalsIgnoreCase(f.drive))) {
      throw XProcException.xd(75, "Drive letter of '" + filepath
          + "' differs from the drive letter of the base URI");
    }
    if ((f.drive != null && b.authority != null) || (f.authority != null && b.drive != null)) {
      throw XProcException.xd(76, "Incompatible authority/drive letter in '" + filepath + "'");
    }
    if (f.scheme != null && !f.scheme.equalsIgnoreCase(b.scheme)) {
      throw XProcException.xd(77, "Scheme of '" + filepath
          + "' differs from the scheme of the base URI");
    }
    if (f.scheme == null || !f.explicitScheme) {
      f.path = fixup(f.path);
    }
    if (baseImplicit) {
      b.path = fixup(b.path);
    }
    Features r = new Features();
    r.scheme = b.scheme;
    r.explicitScheme = true;
    r.authority = f.authority != null ? f.authority : b.authority;
    r.drive = b.drive;
    r.suffix = f.suffix;
    if (f.authority != null) {
      // a reference with an authority does not inherit the base path (RFC 3986, 5.2.2)
      r.path = removeDotSegments(f.path);
    } else if (f.absolute) {
      r.path = f.path;
      if (r.authority == null && b.path.startsWith("//") && !f.path.startsWith("//")) {
        // keep the authority of a (non-file) base URI
        String rest = b.path.substring(2);
        int slash = rest.indexOf('/');
        r.path = "//" + (slash < 0 ? rest : rest.substring(0, slash)) + f.path;
      }
    } else {
      String resolved;
      if (b.path.endsWith("/") || f.path.isEmpty()) {
        resolved = b.path + f.path;
      } else {
        resolved = b.path.substring(0, b.path.lastIndexOf('/') + 1) + f.path;
      }
      r.path = removeDotSegments(resolved);
    }
    r.absolute = r.path.startsWith("/");
    return construct(r);
  }

  private boolean willBeFileUri(String fp, String basedir) {
    String lower = fp.toLowerCase(Locale.ROOT);
    if (lower.startsWith("file:")) {
      return true;
    }
    if (windows && fp.length() >= 2 && Character.isLetter(fp.charAt(0)) && fp.charAt(1) == ':') {
      return true;
    }
    if (SCHEME.matcher(fp).matches() && (fp.indexOf(':') > 1 || !windows)) {
      return false;
    }
    if (basedir == null || basedir.isEmpty()) {
      return true;
    }
    Matcher m = SCHEME.matcher(basedir);
    if (m.matches() && basedir.indexOf(':') > 1
        && !"file".equalsIgnoreCase(m.group(1))) {
      return false;
    }
    return true;
  }

  private Features analyze(String fp) {
    Features f = new Features();
    if (fp.isEmpty()) {
      f.path = "";
      return f;
    }
    if ("//".equals(fp)) {
      f.path = "/";
      f.absolute = true;
      return f;
    }
    Matcher m;
    if (windows && (m = WIN_DRIVE.matcher(fp)).matches()) {
      f.scheme = "file";
      f.explicitScheme = m.group(1) != null;
      f.drive = m.group(2);
      setPath(f, m.group(3) == null ? "" : m.group(3));
      return f;
    }
    if ((m = FILE_AUTH.matcher(fp)).matches()) {
      f.scheme = "file";
      f.explicitScheme = true;
      f.authority = m.group(1);
      String p = m.group(2);
      if (p != null && p.startsWith("/")) {
        setPath(f, p);
      } else {
        f.path = "";
      }
      return f;
    }
    if ((m = FILE_ANY.matcher(fp)).matches()) {
      f.scheme = "file";
      f.explicitScheme = true;
      setPath(f, m.group(1));
      return f;
    }
    if ((m = SCHEME.matcher(fp)).matches() && (m.group(1).length() > 1 || !windows)) {
      f.scheme = m.group(1);
      f.explicitScheme = true;
      f.path = m.group(2);
      f.absolute = f.path.startsWith("/");
      f.hierarchical = switch (f.scheme.toLowerCase(Locale.ROOT)) {
        case "http", "https", "ftp", "file", "jar", "zip" -> true;
        case "mailto", "urn", "doi", "data", "tel", "news" -> false;
        default -> f.path.contains("/");
      };
      return f;
    }
    if ((m = AUTH.matcher(fp)).matches()) {
      f.authority = m.group(1);
      String p = m.group(2);
      if (p != null && p.startsWith("/")) {
        setPath(f, p);
      } else {
        f.path = "";
      }
      return f;
    }
    setPath(f, fp);
    return f;
  }

  private static void setPath(Features f, String p) {
    if (p.startsWith("/")) {
      int i = 0;
      while (i < p.length() && p.charAt(i) == '/') {
        i++;
      }
      f.path = "/" + p.substring(i);
      f.absolute = true;
    } else {
      f.path = p;
      f.absolute = false;
    }
  }

  private static void splitSuffix(Features f) {
    int q = f.path.indexOf('?');
    int h = f.path.indexOf('#');
    int i = q < 0 ? h : (h < 0 ? q : Math.min(q, h));
    if (i >= 0) {
      f.suffix = escapeHtmlUri(f.path.substring(i));
      f.path = f.path.substring(0, i);
    }
  }

  private static String construct(Features f) {
    StringBuilder sb = new StringBuilder();
    if (f.scheme != null) {
      sb.append(f.scheme).append(':');
    }
    if (f.authority != null) {
      sb.append("//").append(f.authority);
    } else if ("file".equalsIgnoreCase(f.scheme) && f.absolute) {
      sb.append("//");
      if (f.drive != null) {
        sb.append('/');
      }
    }
    if (f.drive != null) {
      sb.append(f.drive).append(':');
    }
    sb.append(f.path);
    sb.append(f.suffix);
    return sb.toString();
  }

  static String fixup(String path) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      switch (c) {
        case '?' -> sb.append("%3F");
        case '#' -> sb.append("%23");
        case '\\' -> sb.append("%5C");
        case ' ' -> sb.append("%20");
        case '%' -> {
          if (i + 2 < path.length() && isHex(path.charAt(i + 1)) && isHex(path.charAt(i + 2))) {
            int v = Integer.parseInt(path.substring(i + 1, i + 3), 16);
            if (isUnreserved((char) v)) {
              sb.append((char) v);
            } else {
              sb.append(path, i, i + 3);
            }
            i += 2;
          } else {
            sb.append(c);
          }
        }
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  private static boolean isHex(char c) {
    return Character.digit(c, 16) >= 0;
  }

  private static boolean isUnreserved(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
        || c == '-' || c == '.' || c == '_' || c == '~';
  }

  static String escapeHtmlUri(String s) {
    StringBuilder sb = new StringBuilder();
    for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      int v = b & 0xff;
      if (v < 32 || v > 126) {
        sb.append('%').append(String.format("%02X", v));
      } else {
        sb.append((char) v);
      }
    }
    return sb.toString();
  }

  /** RFC 3986, section 5.2.4. */
  static String removeDotSegments(String path) {
    String input = path;
    Deque<String> output = new ArrayDeque<>();
    StringBuilder out = new StringBuilder();
    while (!input.isEmpty()) {
      if (input.startsWith("../")) {
        input = input.substring(3);
      } else if (input.startsWith("./")) {
        input = input.substring(2);
      } else if (input.startsWith("/./")) {
        input = input.substring(2);
      } else if (input.equals("/.")) {
        input = "/";
      } else if (input.startsWith("/../")) {
        input = input.substring(3);
        if (!output.isEmpty()) {
          output.removeLast();
        }
      } else if (input.equals("/..")) {
        input = "/";
        if (!output.isEmpty()) {
          output.removeLast();
        }
      } else if (input.equals(".") || input.equals("..")) {
        input = "";
      } else {
        int start = input.startsWith("/") ? 1 : 0;
        int next = input.indexOf('/', start);
        String seg = next < 0 ? input : input.substring(0, next);
        output.addLast(seg);
        input = next < 0 ? "" : input.substring(next);
      }
    }
    for (String s : output) {
      out.append(s);
    }
    return out.toString();
  }
}
