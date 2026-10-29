package pe.invoicing.app.config.security.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.security.routes")
public record SecurityRoutes(List<String> publicPaths) {
  public SecurityRoutes {
    publicPaths = List.copyOf(publicPaths);
  }
}
