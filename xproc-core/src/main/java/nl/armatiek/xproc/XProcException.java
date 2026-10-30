package nl.armatiek.xproc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.XdmNode;

/**
 * An error raised during static analysis or evaluation of a pipeline. Every error carries an
 * error code (a QName, usually in the {@code err:} namespace) as defined by the XProc
 * specifications.
 */
public class XProcException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final QName code;
  private QName cause;
  private String location;
  private int line = -1;
  private int column = -1;
  private String stepName;
  private QName stepType;
  private final List<XdmNode> details = new ArrayList<>();

  public XProcException(QName code, String message) {
    super(message);
    this.code = code;
  }

  public XProcException(QName code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** Static error {@code err:XSnnnn}. */
  public static XProcException xs(int number, String message) {
    return new XProcException(errCode("XS", number), message);
  }

  /** Dynamic error {@code err:XDnnnn}. */
  public static XProcException xd(int number, String message) {
    return new XProcException(errCode("XD", number), message);
  }

  /** Dynamic error {@code err:XDnnnn} with an underlying cause. */
  public static XProcException xd(int number, String message, Throwable cause) {
    return new XProcException(errCode("XD", number), message, cause);
  }

  /** Step error {@code err:XCnnnn}. */
  public static XProcException xc(int number, String message) {
    return new XProcException(errCode("XC", number), message);
  }

  /** Step error {@code err:XCnnnn} with an underlying cause. */
  public static XProcException xc(int number, String message, Throwable cause) {
    return new XProcException(errCode("XC", number), message, cause);
  }

  public static QName errCode(String kind, int number) {
    return new QName("err", Ns.XPROC_ERROR, String.format("%s%04d", kind, number));
  }

  /** The error code. */
  public QName getCode() {
    return code;
  }

  /** True if this is a static error (code {@code err:XS*}). */
  public boolean isStatic() {
    return Ns.XPROC_ERROR.equals(code.getNamespace()) && code.getLocalName().startsWith("XS");
  }

  /** The underlying error code (for example an XPath error code), if any. */
  public QName getCauseCode() {
    return cause;
  }

  public XProcException setCauseCode(QName cause) {
    this.cause = cause;
    return this;
  }

  public String getLocation() {
    return location;
  }

  public int getLine() {
    return line;
  }

  public int getColumn() {
    return column;
  }

  /** Records the location in a pipeline document, unless a location is known already. */
  public XProcException at(String uri, int line, int column) {
    if (location == null && line < 0) {
      this.location = uri;
      this.line = line;
      this.column = column;
    }
    return this;
  }

  public String getStepName() {
    return stepName;
  }

  public QName getStepType() {
    return stepType;
  }

  /** Records the step in which the error occurred, unless one is known already. */
  public XProcException inStep(String name, QName type) {
    if (stepType == null) {
      this.stepName = name;
      this.stepType = type;
    }
    return this;
  }

  /** Additional XML content describing the error (for example the documents passed to p:error). */
  public List<XdmNode> getDetails() {
    return Collections.unmodifiableList(details);
  }

  public XProcException addDetail(XdmNode node) {
    details.add(node);
    return this;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(code.getEQName().startsWith("Q{" + Ns.XPROC_ERROR + "}")
        ? "err:" + code.getLocalName() : code.getEQName());
    sb.append(": ").append(getMessage());
    if (stepType != null) {
      sb.append(" [step ").append(stepName).append(" (").append(stepType.getEQName()
          .replace("Q{" + Ns.XPROC + "}", "p:")).append(")]");
    }
    if (location != null) {
      sb.append(" at ").append(location);
      if (line > 0) {
        sb.append(':').append(line);
        if (column > 0) {
          sb.append(':').append(column);
        }
      }
    }
    return sb.toString();
  }
}
