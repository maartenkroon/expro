package nl.armatiek.xproc.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.XProcConfiguration;
import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.rest.service.RunMessages;

/** Creates the shared XProc processor and mounts the API controllers under the server URL. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  /** The server URL of the API, as declared in openapi.yaml. */
  public static final String API_BASE_PATH = "/api/v1";

  @Bean
  public XProcProcessor xprocProcessor(XProcProperties props) {
    MessageListener listener = RunMessages::message;
    XProcConfiguration config = new XProcConfiguration()
        .setAllowFileAccess(props.allowFileAccess())
        .setAllowNetworkAccess(props.allowNetworkAccess())
        .setMessageListener(listener);
    if (props.locale() != null) {
      config.setLocale(props.locale());
    }
    return new XProcProcessor(config);
  }

  @Override
  public void configurePathMatch(PathMatchConfigurer configurer) {
    configurer.addPathPrefix(API_BASE_PATH,
        HandlerTypePredicate.forBasePackage("nl.armatiek.xproc.rest.controller"));
  }
}
