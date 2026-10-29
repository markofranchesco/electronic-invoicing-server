package pe.invoicing.app.config.security.http;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import pe.invoicing.app.config.security.filter.AuthFilter;

@Configuration(proxyBeanMethods = false)
public class HttpSecurityConfig {
  @Bean
  FilterRegistrationBean<AuthFilter> disableServletRegistration(AuthFilter filter) {
    FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, AuthFilter filter, SecurityRoutes securityRoutes)
          throws Exception {
    return http.cors(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(
                    sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(
                    auth ->
                            auth.requestMatchers(org.springframework.http.HttpMethod.GET, "/public/documents/**").permitAll()
                                    .requestMatchers("/sign-up", "/sign-in", "/error")
                                    .permitAll()
                                    .requestMatchers(securityRoutes.publicPaths().toArray(String[]::new))
                                    .permitAll()
                                    .requestMatchers(
                                            "/sign-out", "/sessions", "/sessions/**", "/companies", "/companies/**", "/v2/companies/**")
                                    .authenticated()
                                    .anyRequest()
                                    .denyAll())
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
            .build();
  }
}
