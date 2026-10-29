package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pe.invoicing.app.config.observability.ApplicationTrace;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationTraceTest extends AbstractServiceTest {
  private ApplicationTrace trace() {
    return new ApplicationTrace(new MockEnvironment()
            .withProperty("app.observability.masking.rules[0].fields[0]", "documentNumber")
            .withProperty("app.observability.masking.rules[0].mode", "PARTIAL")
            .withProperty("app.observability.masking.rules[0].keep-suffix", "4")
            .withProperty("app.observability.masking.rules[1].fields[0]", "email")
            .withProperty("app.observability.masking.rules[1].mode", "REGEX")
            .withProperty("app.observability.masking.rules[1].pattern", "^([^@])[^@]*(@.+)$")
            .withProperty("app.observability.masking.rules[1].replacement", "$1***$2"));
  }

  @Test
  @DisplayName("Ofuscar objetos anidados")
  void whenNestedSecretsThenMaskBeforeLogging() {
    var result = trace().sanitize(Map.of("Authorization", "Bearer secret", "customer",
            Map.of("documentNumber", "20123456786", "email", "ana@example.com", "password", "clave")));
    assertThat(result.toString()).contains("***6786", "a***@example.com")
            .doesNotContain("Bearer secret", "clave", "20123456786", "ana@example.com");
  }

  @Test
  @DisplayName("Correlacionar y restaurar el hilo")
  void whenRequestIdProvidedThenReturnItAndRestoreContext() throws Exception {
    var request = new MockHttpServletRequest("POST", "/companies/20123456786/documents/issue");
    request.addHeader("X-Request-ID", "front-123");
    request.setContentType("application/json");
    request.setContent("{\"password\":\"secret\"}".getBytes(StandardCharsets.UTF_8));
    var response = new MockHttpServletResponse();
    MDC.put("requestId", "previous");
    try {
      trace().doFilter(request, response, (input, output) -> {
        assertThat(MDC.get("requestId")).isEqualTo("front-123");
        input.getInputStream().readAllBytes();
        output.setContentType("application/json");
        output.getWriter().write("{\"ok\":true}");
      });
      assertThat(response.getHeader("X-Request-ID")).isEqualTo("front-123");
      assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
      assertThat(MDC.get("requestId")).isEqualTo("previous");
    } finally {
      MDC.clear();
    }
  }

  @Test
  @DisplayName("Rechazar identificador inseguro")
  void whenRequestIdInvalidThenGenerateIdentifier() throws Exception {
    var request = new MockHttpServletRequest("GET", "/companies");
    request.addHeader("X-Request-ID", "bad\r\ninjected");
    var response = new MockHttpServletResponse();
    trace().doFilter(request, response, (input, output) -> {
    });
    assertThat(response.getHeader("X-Request-ID")).matches("[a-f0-9-]{36}");
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  @DisplayName("Identificador en ProblemDetail")
  void whenProblemDetailThenIncludeRequestId() {
    MDC.put("requestId", "front-error");
    try {
      var problem = ProblemDetail.forStatus(502);
      assertThat(trace().beforeBodyWrite(problem, null, null, null, null, null)).isSameAs(problem);
      assertThat(problem.getProperties()).containsEntry("requestId", "front-error");
    } finally {
      MDC.clear();
    }
  }

  @Test
  @DisplayName("No registrar cuerpos incompletos")
  void whenInvalidOrOversizedBodyThenOmitContent() {
    var trace = trace();
    assertThat(trace.body("{\"password\":\"secret".getBytes(StandardCharsets.UTF_8), "application/json").toString())
            .doesNotContain("secret");
    assertThat(trace.body(new byte[9000], "application/json").toString()).contains("omitted");
  }

  @Test
  @DisplayName("Aspecto conserva el error original")
  void whenMethodFailsThenRethrowSameFailureAndCleanContext() throws Throwable {
    var join = mock(org.aspectj.lang.ProceedingJoinPoint.class);
    var signature = mock(org.aspectj.lang.Signature.class);
    when(join.getSignature()).thenReturn(signature);
    when(signature.toShortString()).thenReturn("Service.issue()");
    when(join.getArgs()).thenReturn(new Object[0]);
    var failure = new IllegalStateException("password=secret");
    when(join.proceed()).thenThrow(failure);
    assertThatThrownBy(() -> trace().around(join)).isSameAs(failure);
    assertThat(MDC.get("requestId")).isNull();
    verify(join).proceed();
  }

  @Test
  @DisplayName("Ofuscar RUC incrustado y rutas")
  void whenReferenceAndProblemInstanceThenMaskEmbeddedTaxpayer() {
    assertThat(trace().sanitize(Map.of("expectedReference", "20123456786-01-F001-1",
            "instance", "/companies/20123456786/documents/issue")).toString())
            .doesNotContain("20123456786").contains("***6786-01-F001-1");
  }

  @Test
  @DisplayName("Ofuscar XML SOAP")
  void whenSoapBodyContainsCredentialsThenRemoveSecrets() {
    String xml = "<Envelope><Password>secret</Password><Username>user-secret</Username>"
            + "<ReceiverParty><IdentificationID>20123456786</IdentificationID></ReceiverParty></Envelope>";
    assertThat(trace().body(xml.getBytes(StandardCharsets.UTF_8), "text/xml").toString())
            .doesNotContain("secret", "20123456786").contains("***6786");
  }

  @Test
  @DisplayName("Preservar descarga sin retener todo el archivo")
  void whenLargeDownloadThenReturnEveryOriginalByte() throws Exception {
    var content = new byte[20_000];
    java.util.Arrays.fill(content, (byte) 42);
    var response = new MockHttpServletResponse();
    trace().doFilter(new MockHttpServletRequest("GET", "/files"), response, (input, output) -> {
      output.setContentType("application/pdf");
      output.getOutputStream().write(content);
    });
    assertThat(response.getContentAsByteArray()).isEqualTo(content);
  }

  @Test
  @DisplayName("Restaurar contexto ante fallo del filtro")
  void whenFilterChainThrowsThenPropagateAndRemoveCorrelation() {
    assertThatThrownBy(() -> trace().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
            (input, output) -> {
              throw new jakarta.servlet.ServletException("failure");
            }))
            .isInstanceOf(jakarta.servlet.ServletException.class);
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  @DisplayName("Validar cuotas sin anotación obsoleta")
  void whenInstallmentInvalidThenKeepNestedValidation() {
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      var request = pe.invoicing.app.controller.document.dtos.requests.DocumentUpsertRequest.builder()
              .installments(java.util.List.of(pe.invoicing.app.controller.document.dtos.shared.InstallmentInput.builder()
                      .amount("0").build())).build();
      assertThat(factory.getValidator().validate(request)).anySatisfy(violation ->
              assertThat(violation.getPropertyPath().toString()).isEqualTo("installments[0].amount"));
      assertThat(request.getClass().getDeclaredField("installments").isAnnotationPresent(jakarta.validation.Valid.class)).isFalse();
    } catch (NoSuchFieldException exception) {
      throw new AssertionError(exception);
    }
  }

  @Test
  @DisplayName("Conservar identificador en despacho de error")
  void whenErrorDispatchThenReuseOriginalIdentifier() throws Exception {
    var request = new MockHttpServletRequest("GET", "/error");
    request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
    request.setAttribute("requestId", "original-error");
    request.setAttribute("jakarta.servlet.error.request_uri", "/companies");
    var response = new MockHttpServletResponse();
    trace().doFilter(request, response, (input, output) ->
            assertThat(MDC.get("requestId")).isEqualTo("original-error"));
    assertThat(response.getHeader("X-Request-ID")).isEqualTo("original-error");
  }

  @Test
  @DisplayName("Límite de log después de ofuscar")
  void whenEventExceedsLimitThenKeepSafeMetadata() {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApplicationTrace.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      new ApplicationTrace(new MockEnvironment().withProperty("app.observability.limits.max-log-chars", "128"))
              .event("test.limit", Map.of("description", "x".repeat(500), "password", "secret"));
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getKeyValuePairs().toString())
              .contains("max-log-chars").doesNotContain("secret", "x".repeat(500));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("Aplicar aspecto con proxy Spring real")
  void whenGatewayProxiedThenTraceWithoutChangingInvocation() {
    var context = mock(pe.invoicing.app.config.sunat.credentials.SunatEmissionContext.class);
    var target = new pe.invoicing.app.config.sunat.soap.SunatSoapGateway(
            mock(pe.invoicing.app.config.sunat.xml.SunatXml.class), context,
            new pe.invoicing.app.config.sunat.properties.SunatProperties(
                    java.net.URI.create("http://localhost"), java.net.URI.create("http://localhost"), new MockEnvironment()), trace().sunatClient());
    var factory = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAspect(trace());
    pe.invoicing.app.config.sunat.soap.SunatSoapGateway proxy = factory.getProxy();
    proxy.checkReady(COMPANY);
    verify(context).sunatCredentials(COMPANY);
    verify(context).signingMaterial(COMPANY);
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  @DisplayName("No confundir sesión con comprobante")
  void whenSessionUuidThenDoNotLabelItAsDocument() throws Throwable {
    var join = mock(org.aspectj.lang.ProceedingJoinPoint.class);
    var signature = mock(org.aspectj.lang.Signature.class);
    when(join.getSignature()).thenReturn(signature);
    when(signature.toShortString()).thenReturn("SessionService.revoke()");
    when(signature.getDeclaringTypeName()).thenReturn("pe.invoicing.app.service.session.impl.SessionServiceImpl");
    when(join.getArgs()).thenReturn(new Object[]{java.util.UUID.randomUUID()});
    when(join.proceed()).thenAnswer(invocation -> {
      assertThat(MDC.get("documentId")).isNull();
      return null;
    });
    trace().around(join);
    verify(join).proceed();
  }

  @Test
  @DisplayName("Una traza por método con entrada y salida")
  void whenMethodCompletesThenLogParametersAndResponseTogether() throws Throwable {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApplicationTrace.class);
    var oldLevel = logger.getLevel();
    logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var join = mock(org.aspectj.lang.ProceedingJoinPoint.class);
      var signature = mock(org.aspectj.lang.reflect.MethodSignature.class);
      when(join.getSignature()).thenReturn(signature);
      when(signature.toShortString()).thenReturn("Service.issue(..)");
      when(signature.getParameterNames()).thenReturn(new String[]{"request"});
      when(join.getArgs()).thenReturn(new Object[]{Map.of("password", "hidden", "quantity", 2)});
      when(join.proceed()).thenReturn(Map.of("status", "UNKNOWN"));
      trace().around(join);
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getKeyValuePairs().toString())
              .contains("parameters", "response", "quantity", "UNKNOWN").doesNotContain("hidden", "details=");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(oldLevel);
    }
  }

  @Test
  @DisplayName("Una traza HTTP con query y variables de ruta")
  void whenHttpCompletesThenIncludeAllInputSourcesOnce() throws Exception {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApplicationTrace.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var request = new MockHttpServletRequest("GET", "/companies/20123456786/documents");
      request.setQueryString("page=2&accessToken=secret");
      request.addHeader("User-Agent", "test-client");
      request.setAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
              Map.of("documentNumber", "20123456786"));
      trace().doFilter(request, new MockHttpServletResponse(), (input, output) -> {
      });
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getKeyValuePairs().toString())
              .contains("parameters", "query", "page", "pathVariables", "headers", "test-client")
              .doesNotContain("secret", "20123456786");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("Capturar cabeceras y cuerpo HTTP externo una sola vez")
  void whenExternalFaultThenCaptureHeadersAndMaskEchoedCredentials() throws Exception {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApplicationTrace.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var request = new org.springframework.mock.http.client.MockClientHttpRequest(
              org.springframework.http.HttpMethod.POST, java.net.URI.create("http://localhost/service?email=ana%40example.com"));
      request.getHeaders().setContentType(org.springframework.http.MediaType.TEXT_XML);
      request.getHeaders().setBearerAuth("secret-bearer");
      request.getHeaders().set("X-Test", "visible");
      byte[] sent = "<Envelope><Username>hidden-user</Username><Password>hidden-password</Password></Envelope>".getBytes(StandardCharsets.UTF_8);
      var received = new org.springframework.mock.http.client.MockClientHttpResponse(
              "<Fault><faultstring>hidden-password</faultstring></Fault>".getBytes(StandardCharsets.UTF_8), org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
      received.getHeaders().setContentType(org.springframework.http.MediaType.TEXT_XML);
      var execution = mock(org.springframework.http.client.ClientHttpRequestExecution.class);
      when(execution.execute(request, sent)).thenReturn(received);
      try (var response = trace().intercept(request, sent, execution)) {
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)).contains("hidden-password");
      }
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getKeyValuePairs().toString()).contains("visible", "500", "parameters", "response")
              .doesNotContain("hidden-password", "secret-bearer", "hidden-user", "ana@example.com");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("Mostrar contenido CDR como XML ofuscado")
  void whenSoapContainsZippedCdrThenExposeSafeDecodedResponse() throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
      zip.putNextEntry(new java.util.zip.ZipEntry("R-response.xml"));
      zip.write("<ApplicationResponse><ResponseCode>0</ResponseCode><password>hidden</password></ApplicationResponse>".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    String encoded = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    String xml = "<Envelope><applicationResponse>" + encoded + "</applicationResponse></Envelope>";
    assertThat(trace().body(xml.getBytes(StandardCharsets.UTF_8), "text/xml").toString())
            .contains("ResponseCode=0").doesNotContain("hidden", encoded);
  }

  @Test
  @DisplayName("Ofuscar nombres reales del dominio")
  void whenDomainCredentialsThenMaskCertificatePasswordAndRawToken() {
    assertThat(trace().sanitize(Map.of("certificatePassword", "cert-secret", "rawToken", "bearer-secret",
            "solUsername", "sol-user", "passwordHash", "hash-secret")).toString())
            .doesNotContain("cert-secret", "bearer-secret", "sol-user", "hash-secret");
  }

  @Test
  @DisplayName("No registrar token devuelto por autenticación")
  void whenAuthenticationReturnsTokenThenMaskStringResponse() throws Throwable {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ApplicationTrace.class);
    var oldLevel = logger.getLevel();
    logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var join = mock(org.aspectj.lang.ProceedingJoinPoint.class);
      var signature = mock(org.aspectj.lang.reflect.MethodSignature.class);
      when(join.getSignature()).thenReturn(signature);
      when(signature.toShortString()).thenReturn("AutenticacionServiceImpl.signIn(..)");
      when(signature.getDeclaringTypeName()).thenReturn("pe.invoicing.app.service.authentication.impl.AutenticacionServiceImpl");
      when(signature.getParameterNames()).thenReturn(new String[0]);
      when(join.getArgs()).thenReturn(new Object[0]);
      when(join.proceed()).thenReturn("returned-secret-token");
      assertThat(trace().around(join)).isEqualTo("returned-secret-token");
      assertThat(appender.list).hasSize(1);
      assertThat(appender.list.getFirst().getKeyValuePairs().toString()).doesNotContain("returned-secret-token");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(oldLevel);
    }
  }

}
