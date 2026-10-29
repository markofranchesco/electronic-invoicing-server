package pe.invoicing.app.config.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration(proxyBeanMethods = false)
public class TimeConfig {
  @Bean
  Clock clock() {
    return Clock.system(ZoneId.of("America/Lima"));
  }
}
