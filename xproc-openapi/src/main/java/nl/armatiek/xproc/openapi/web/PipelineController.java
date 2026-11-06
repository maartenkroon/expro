package nl.armatiek.xproc.openapi.web;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nl.armatiek.xproc.XProcException;
import nl.armatiek.xproc.XProcPipeline;
import nl.armatiek.xproc.XProcResult;
import nl.armatiek.xproc.openapi.ApiRegistry;
import nl.armatiek.xproc.openapi.PipelineCache;
import nl.armatiek.xproc.openapi.api.ApiDescription;
import nl.armatiek.xproc.openapi.api.OperationSpec;

/**
 * Handles the requests of the API (see {@link ApiHandlerMapping}): finds the operation of the
 * OpenAPI description, runs its pipeline and writes the result or a problem detail. Requests are
 * independent; the shared description, compiled pipelines and processor are thread-safe.
 */
@Component
public class PipelineController implements HttpRequestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(PipelineController.class);

  private final ApiRegistry registry;
  private final PipelineCache pipelines;
  private final RequestMapper requests;
  private final ResponseWriter responses;
  private final ProblemWriter problems;

  public PipelineController(ApiRegistry registry, PipelineCache pipelines,
      RequestMapper requests, ResponseWriter responses, ProblemWriter problems) {
    this.registry = registry;
    this.pipelines = pipelines;
    this.requests = requests;
    this.responses = responses;
    this.problems = problems;
  }

  @Override
  public void handleRequest(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    OperationSpec operation = null;
    XProcPipeline pipeline = null;
    try {
      ApiDescription api = registry.get();
      ApiDescription.Match match = api.find(request.getMethod(), RequestMapper.path(request));
      operation = match.operation();
      if (operation.pipeline() == null) {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "The operation " + request
            .getMethod() + " " + operation.pathTemplate() + " has no pipeline: "
            + (operation.operationId() == null ? "it has no operationId"
                : "its operationId cannot be a file name"));
      }
      pipeline = pipelines.get(operation.pipeline());
      XProcResult result = requests.map(request, api, match, pipeline).run();
      responses.write(pipeline, result, response);
    } catch (ApiException e) {
      if (e.getStatus().is5xxServerError()) {
        LOG.error("{} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
      }
      problem(request, response, pipeline, operation, e.getStatus(), e.getMessage(),
          e.getHeaders(), e.getCause());
    } catch (XProcException e) {
      HttpStatus status = e.isCallerError() ? HttpStatus.BAD_REQUEST
          : HttpStatus.INTERNAL_SERVER_ERROR;
      if (status.is5xxServerError()) {
        LOG.error("{} {}: {}", request.getMethod(), request.getRequestURI(), e);
      }
      problem(request, response, pipeline, operation, status, e.getMessage(), Map.of(), e);
    } catch (RuntimeException e) {
      LOG.error("{} {}: unexpected error", request.getMethod(), request.getRequestURI(), e);
      problem(request, response, pipeline, operation, HttpStatus.INTERNAL_SERVER_ERROR,
          e.toString(), Map.of(), e);
    }
  }

  private void problem(HttpServletRequest request, HttpServletResponse response,
      XProcPipeline pipeline, OperationSpec operation, HttpStatus status, String detail,
      Map<String, String> headers, Throwable cause) throws IOException {
    if (response.isCommitted()) {
      LOG.error("Cannot report an error on a response that has been sent: {}", detail);
      return;
    }
    problems.write(response, problems.format(pipeline, operation, request), status, detail,
        request.getRequestURI(), headers, cause);
  }
}
