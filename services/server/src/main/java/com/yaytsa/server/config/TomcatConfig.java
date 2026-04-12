package com.yaytsa.server.config;

import org.apache.catalina.Valve;
import org.apache.catalina.valves.ErrorReportValve;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfig {

  @Bean
  public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatErrorReportCustomizer() {
    return factory ->
        factory.addContextCustomizers(
            context -> {
              Valve[] valves = context.getParent().getPipeline().getValves();
              for (Valve valve : valves) {
                if (valve instanceof ErrorReportValve errorReportValve) {
                  errorReportValve.setShowReport(false);
                  errorReportValve.setShowServerInfo(false);
                }
              }
            });
  }
}
