package nl.armatiek.xproc.openapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import nl.armatiek.xproc.MessageListener;
import nl.armatiek.xproc.XProcConfiguration;
import nl.armatiek.xproc.XProcProcessor;

/**
 * A web service whose API is defined by an OpenAPI description in a configuration directory and
 * whose operations are implemented by the XProc pipelines in that directory.
 */
@SpringBootApplication
@EnableConfigurationProperties(OpenApiProperties.class)
public class XProcOpenApiApplication {

  private static final Logger PIPELINE_LOG = LoggerFactory.getLogger("nl.armatiek.xproc.pipeline");

  public static void main(String[] args) {
    SpringApplication.run(XProcOpenApiApplication.class, args);
  }

  /** The processor shared by all requests (it is thread-safe). */
  @Bean
  public XProcProcessor xprocProcessor(OpenApiProperties props) {
    MessageListener listener = (level, step, message) -> {
      switch (level) {
        case DEBUG -> PIPELINE_LOG.debug("{}: {}", step, message);
        case INFO -> PIPELINE_LOG.info("{}: {}", step, message);
        case WARNING -> PIPELINE_LOG.warn("{}: {}", step, message);
        case ERROR -> PIPELINE_LOG.error("{}: {}", step, message);
      }
    };
    return new XProcProcessor(new XProcConfiguration()
        .setAllowFileAccess(props.allowFileAccess())
        .setAllowNetworkAccess(props.allowNetworkAccess())
        .setAllowOsAccess(props.allowOsAccess())
        .setXsltCacheSize(props.xsltCacheSize())
        .setFopConfiguration(props.fopConfig() == null ? null : props.fopConfig().toUri())
        .setMessageListener(listener));
  }
}
