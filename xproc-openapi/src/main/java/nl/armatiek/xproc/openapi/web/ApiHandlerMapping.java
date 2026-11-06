package nl.armatiek.xproc.openapi.web;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import nl.armatiek.xproc.openapi.ApiRegistry;

/**
 * Routes the requests of the API to the {@link PipelineController}. It comes after the
 * annotated controllers (the description, springdoc, the error page) and before the static
 * resources (Swagger UI), and claims a request only if its path belongs to the API, so a
 * description that is served at the root does not hide the rest of the application.
 */
@Component
public class ApiHandlerMapping extends AbstractHandlerMapping {

  private final ApiRegistry registry;
  private final PipelineController controller;

  public ApiHandlerMapping(ApiRegistry registry, PipelineController controller) {
    this.registry = registry;
    this.controller = controller;
    setOrder(Ordered.LOWEST_PRECEDENCE - 10);
  }

  @Override
  protected Object getHandlerInternal(HttpServletRequest request) {
    return registry.get().claims(RequestMapper.path(request)) ? controller : null;
  }
}
