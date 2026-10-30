package nl.armatiek.xproc;

import net.sf.saxon.s9api.QName;

/**
 * Describes an option of a pipeline.
 *
 * @param name the option name
 * @param type the declared sequence type (null means item()*)
 * @param required true if a value must be supplied
 * @param isStatic true for static options (whose values are fixed at compile time)
 * @param defaultExpression the default value expression, or null
 */
public record OptionSignature(QName name, String type, boolean required, boolean isStatic,
    String defaultExpression) {
}
