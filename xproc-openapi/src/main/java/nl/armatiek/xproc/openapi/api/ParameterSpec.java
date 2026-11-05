package nl.armatiek.xproc.openapi.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import net.sf.saxon.s9api.QName;

/**
 * A parameter of an operation, as far as it matters for passing its value to a pipeline.
 *
 * @param name the parameter name
 * @param in the location: path, query, header or cookie
 * @param required true if the parameter must be present
 * @param array true if the schema of the parameter is an array, so its value is a sequence
 * @param style the serialization style (form, simple, spaceDelimited, ...)
 * @param explode whether array values are given as repeated parameters
 * @param defaultValues the default value of the schema (one string per array item), if any
 * @param optionName the name of the pipeline option that receives the value
 */
public record ParameterSpec(String name, String in, boolean required, boolean array,
    String style, boolean explode, List<String> defaultValues, QName optionName) {

  /** The values of a parameter given as a single string (one value per array item). */
  public List<String> split(String value) {
    if (!array) {
      return List.of(value);
    }
    String separator = switch (style) {
      case "spaceDelimited" -> " ";
      case "pipeDelimited" -> "|";
      default -> ",";
    };
    List<String> values = new ArrayList<>();
    for (String v : value.split(Pattern.quote(separator), -1)) {
      if (!v.isEmpty()) {
        values.add(v);
      }
    }
    return values;
  }
}
