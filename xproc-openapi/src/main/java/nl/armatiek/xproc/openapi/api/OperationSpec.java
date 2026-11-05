package nl.armatiek.xproc.openapi.api;

import java.nio.file.Path;
import java.util.List;

import org.springframework.web.util.pattern.PathPattern;

/**
 * An operation of the OpenAPI description and the pipeline that implements it.
 *
 * @param method the HTTP method (upper case)
 * @param pathTemplate the path template of the operation, as in the description
 * @param pattern the compiled path template
 * @param operationId the operationId (null if the operation has none)
 * @param pipeline the pipeline file (operationId.xpl in the configuration directory), or null if
 *        the operation has no usable operationId
 * @param parameters the parameters of the path item and the operation
 * @param responseContentTypes the media types of the responses of the operation, success
 *        responses first
 */
public record OperationSpec(String method, String pathTemplate, PathPattern pattern,
    String operationId, Path pipeline, List<ParameterSpec> parameters,
    List<String> responseContentTypes) {
}
