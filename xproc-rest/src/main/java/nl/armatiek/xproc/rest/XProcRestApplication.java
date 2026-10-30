package nl.armatiek.xproc.rest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot application that exposes the XProc processor through the REST interface defined
 * in {@code static/openapi.yaml}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class XProcRestApplication {

  public static void main(String[] args) {
    SpringApplication.run(XProcRestApplication.class, args);
  }
}
