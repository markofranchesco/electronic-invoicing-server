package pe.invoicing.app.config.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.databind.json.JsonMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Una única política para correlación, captura acotada y ofuscación de trazas.
 */
@Slf4j
@Aspect
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApplicationTrace extends OncePerRequestFilter implements ResponseBodyAdvice<Object> {
  public static final String HEADER = "X-Request-ID";
  private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  private static final Set<String> SECRETS = Set.of("password", "authorization", "proxyauthorization",
          "accesstoken", "refreshtoken", "cookie", "setcookie", "clientsecret", "secret", "privatekey",
          "contentfile", "applicationresponse", "content", "signaturevalue", "x509certificate",
          "base64", "certificate", "publicationkey", "key", "username", "solpassword", "soluser", "user", "taxcredentials", "digitalcertificate", "token", "bearertoken", "cdrzip", "keyhash", "bodyhash", "apikey", "xapikey", "xauthtoken");
  private final int maxBodyBytes;
  private final int maxLogChars;
  private final String replacement;
  private final List<Rule> rules;
  private final ThreadLocal<Integer> archiveDepth = ThreadLocal.withInitial(() -> 0);

  public ApplicationTrace(Environment environment) {
    var binder = Binder.get(environment);
    maxBodyBytes = Math.clamp(environment.getProperty("app.observability.limits.max-body-bytes", Integer.class, 8192), 1, 1_048_576);
    maxLogChars = Math.clamp(environment.getProperty("app.observability.limits.max-log-chars", Integer.class, 4096), 128, 1_048_576);
    replacement = environment.getProperty("app.observability.masking.replacement", "***");
    rules = binder.bind("app.observability.masking.rules",
            org.springframework.boot.context.properties.bind.Bindable.listOf(Rule.class)).orElse(List.of());
    for (var rule : rules) {
      if (rule.fields() == null || !Set.of("FULL", "PARTIAL", "REGEX").contains(rule.mode()))
        throw new IllegalArgumentException("Regla de ofuscación inválida");
      if ("REGEX".equals(rule.mode())) Pattern.compile(Objects.requireNonNull(rule.pattern()));
      if (rule.keepPrefix() < 0 || rule.keepSuffix() < 0)
        throw new IllegalArgumentException("Prefijo o sufijo inválido");
    }
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static void restore(Map<String, String> context) {
    MDC.clear();
    if (context != null) MDC.setContextMap(context);
  }

  @org.springframework.context.annotation.Bean
  public org.springframework.boot.web.servlet.FilterRegistrationBean<ApplicationTrace> traceRegistration() {
    var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(this);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ERROR);
    return registration;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
    var previous = MDC.getCopyOfContextMap();
    String incoming = request.getAttribute("requestId") instanceof String existing
            ? existing : request.getHeader(HEADER);
    String id = incoming != null && incoming.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
            ? incoming : UUID.randomUUID().toString();
    MDC.put("requestId", id);
    request.setAttribute("requestId", id);
    response.setHeader(HEADER, id);
    var input = new ContentCachingRequestWrapper(request, maxBodyBytes + 1);
    var output = new CaptureResponse(response, maxBodyBytes + 1);
    long started = System.nanoTime();
    boolean completed = false;
    try {
      chain.doFilter(input, output);
      completed = true;
    } finally {
      try {
        output.finish();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("method", Objects.toString(request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE), request.getMethod()));
        fields.put("httpMethod", request.getMethod());
        fields.put("path", safePath(request.getRequestURI()));
        fields.put("headers", headers(request));
        fields.put("parameters", Map.of("pathVariables", Objects.requireNonNullElse(
                        request.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE), Map.of()),
                "query", query(request.getQueryString()),
                "body", body(input.getContentAsByteArray(), input.getContentType())));
        fields.put("response", Map.of("status", completed || output.getStatus() >= 400 ? output.getStatus() : 500,
                "headers", responseHeaders(output), "body", body(output.captured.toByteArray(), output.getContentType())));
        fields.put("durationMs", elapsed(started));
        if (request.getAttribute("trace.error") != null) fields.put("error", request.getAttribute("trace.error"));
        event("http", fields);
      } finally {
        restore(previous);
      }
    }
  }

  @Around("execution(public * pe.invoicing.app.service..impl..*(..)) || "
          + "execution(public * pe.invoicing.app.config.sunat.soap.SunatSoapGateway.*(..))")
  public Object around(ProceedingJoinPoint join) throws Throwable {
    var previous = MDC.getCopyOfContextMap();
    if (MDC.get("requestId") == null) MDC.put("requestId", UUID.randomUUID().toString());
    // El UUID del comprobante permite enlazar las consultas posteriores del conciliador.
    String declaringType = Objects.requireNonNullElse(join.getSignature().getDeclaringTypeName(), "");
    boolean documentOperation = declaringType.contains(".document.") || declaringType.contains(".cancellation.")
            || declaringType.contains(".creditnote.");
    for (Object argument : join.getArgs()) {
      if (documentOperation && argument instanceof UUID uuid) MDC.put("documentId", uuid.toString());
      if (argument instanceof pe.invoicing.app.service.document.models.DocumentData document && document.getId() != null)
        MDC.put("documentId", document.getId().toString());
    }
    long started = System.nanoTime();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("method", join.getSignature().toShortString());
    fields.put("parameters", org.slf4j.LoggerFactory.getLogger(ApplicationTrace.class.getName() + ".methods").isDebugEnabled() ? parameters(join) : Map.of());
    fields.put("response", null);
    try {
      Object result = join.proceed();
      if (org.slf4j.LoggerFactory.getLogger(ApplicationTrace.class.getName() + ".methods").isDebugEnabled())
        fields.put("response", declaringType.contains(".authentication.") && result instanceof String ? replacement : snapshot(result));
      return result;
    } catch (Throwable failure) {
      fields.put("error", error(failure));
      if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
              instanceof org.springframework.web.context.request.ServletRequestAttributes attributes)
        if (attributes.getRequest().getAttribute("trace.error") == null)
          attributes.getRequest().setAttribute("trace.error", error(failure));
      throw failure;
    } finally {
      fields.put("durationMs", elapsed(started));
      try {
        event("method", fields);
      } finally {
        restore(previous);
      }
    }
  }

  private Map<String, Object> parameters(ProceedingJoinPoint join) {
    Map<String, Object> values = new LinkedHashMap<>();
    String[] names = join.getSignature() instanceof org.aspectj.lang.reflect.MethodSignature signature
            ? signature.getParameterNames() : null;
    Object[] arguments = join.getArgs();
    for (int i = 0; i < arguments.length; i++) {
      String name = names != null && i < names.length ? names[i] : "arg" + i;
      values.put(name, snapshot(arguments[i]));
    }
    return values;
  }

  private Object snapshot(Object value) {
    try {
      if (value == null) return null;
      if (value instanceof byte[] bytes) return body(bytes, "application/xml");
      if (value instanceof InputStream || value instanceof jakarta.servlet.ServletRequest
              || value instanceof jakarta.servlet.ServletResponse || value instanceof org.springframework.core.io.Resource)
        return Map.of("omitted", "stream-or-framework-object");
      return sanitize(JSON.convertValue(value, Object.class));
    } catch (RuntimeException ignored) {
      return Map.of("omitted", "unserializable-object");
    }
  }

  private Map<String, Object> error(Throwable failure) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", failure.getClass().getName());
    if (failure instanceof org.springframework.web.server.ResponseStatusException response) {
      result.put("status", response.getStatusCode().value());
      result.put("message", response.getReason());
      if (response.getCause() != null) {
        result.put("causeType", response.getCause().getClass().getName());
        // El gateway conserva motivos de validación controlados, sin volcar trazas con credenciales.
        if (response.getCause().getClass() == IOException.class) result.put("reason", response.getCause().getMessage());
      }
    }
    return result;
  }

  @Override
  public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType type,
                                Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
    if (body instanceof ProblemDetail problem && MDC.get("requestId") != null)
      problem.setProperty("requestId", MDC.get("requestId"));
    return body;
  }

  public void event(String event, Map<String, ?> values) {
    // Logback serializa el evento. Aquí únicamente se seleccionan y ofuscan sus campos.
    try {
      var logger = "method".equals(event) ? org.slf4j.LoggerFactory.getLogger(ApplicationTrace.class.getName() + ".methods") : log;
      if ("method".equals(event) && !logger.isDebugEnabled()) return;
      boolean failed = values.containsKey("error") || values.get("response") instanceof Map<?, ?> response
              && response.get("status") instanceof Number status && status.intValue() >= 400;
      var entry = "method".equals(event) ? logger.atDebug() : failed ? logger.atWarn() : logger.atInfo();
      entry.addKeyValue("event", event);
      values.forEach((name, value) -> {
        Object safe = sanitize(Collections.singletonMap(name, value));
        Object field = ((Map<?, ?>) safe).get(name);
        if (JSON.writeValueAsString(field).length() > maxLogChars)
          field = Map.of("omitted", "max-log-chars", "limit", maxLogChars);
        entry.addKeyValue(name, field);
      });
      entry.log("exchange");
    } catch (RuntimeException ignored) {
      log.atWarn().addKeyValue("event", "trace.omitted").log("No se pudo representar la traza");
    }
  }

  private Map<String, List<String>> query(String query) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) return values;
    if (query.length() > maxBodyBytes) return Map.of("omitted", List.of("max-body-bytes"));
    try {
      for (String part : query.split("&")) {
        String[] pair = part.split("=", 2);
        String name = java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
        String value = java.net.URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
        values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
      }
      return values;
    } catch (IllegalArgumentException ignored) {
      return Map.of("omitted", List.of("invalid-query"));
    }
  }

  @org.springframework.context.annotation.Bean
  public org.springframework.web.client.RestClient sunatClient() {
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
    factory.setReadTimeout(java.time.Duration.ofSeconds(35));
    return org.springframework.web.client.RestClient.builder().requestFactory(factory)
            .requestInterceptor(this::intercept).build();
  }

  /**
   * Una traza automática por intercambio, incluidas respuestas HTTP 4xx/5xx.
   */
  public org.springframework.http.client.ClientHttpResponse intercept(org.springframework.http.HttpRequest request,
                                                                      byte[] bytes, org.springframework.http.client.ClientHttpRequestExecution execution) throws IOException {
    long started = System.nanoTime();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("method", request.getMethod().name());
    fields.put("path", request.getURI().getScheme() + "://" + request.getURI().getAuthority() + request.getURI().getRawPath());
    fields.put("headers", headerValues(request.getHeaders()));
    fields.put("parameters", Map.of("query", query(request.getURI().getRawQuery()),
            "body", body(bytes, Objects.toString(request.getHeaders().getContentType(), ""))));
    try {
      var response = execution.execute(request, bytes);
      return new org.springframework.http.client.ClientHttpResponse() {
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private InputStream input;
        private boolean closed;

        @Override
        public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
          return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
          return response.getStatusText();
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
          return response.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
          if (input == null) input = new FilterInputStream(response.getBody()) {
            @Override
            public int read() throws IOException {
              int value = in.read();
              if (value >= 0 && captured.size() <= maxBodyBytes) captured.write(value);
              return value;
            }

            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
              int count = in.read(target, offset, length);
              if (count > 0) captured.write(target, offset, Math.min(count, maxBodyBytes + 1 - captured.size()));
              return count;
            }
          };
          return input;
        }

        @Override
        public void close() {
          if (closed) return;
          closed = true;
          try {
            fields.put("response", Map.of("status", getStatusCode().value(), "headers", headerValues(getHeaders()),
                    "body", body(redactEcho(captured.toByteArray(), bytes), Objects.toString(getHeaders().getContentType(), "text/xml"))));
          } catch (IOException ignored) {
            fields.put("response", Map.of("omitted", "response-unavailable"));
          } finally {
            response.close();
            fields.put("durationMs", elapsed(started));
            event("external", fields);
          }
        }
      };
    } catch (IOException | RuntimeException failure) {
      fields.put("error", error(failure));
      fields.put("durationMs", elapsed(started));
      event("external", fields);
      throw failure;
    }
  }

  private Map<String, List<String>> headerValues(org.springframework.http.HttpHeaders headers) {
    Map<String, List<String>> values = new LinkedHashMap<>();
    headers.forEach((name, entries) -> values.put(name, List.copyOf(entries)));
    return values;
  }

  private byte[] redactEcho(byte[] response, byte[] request) {
    if (response.length > maxBodyBytes) return response;
    String text = new String(response, StandardCharsets.UTF_8);
    var matcher = Pattern.compile("<(?:[\\w]+:)?(?:Password|Username)[^>]*>([^<]+)</").matcher(new String(request, StandardCharsets.UTF_8));
    while (matcher.find()) {
      String secret = matcher.group(1);
      text = text.replace(secret, replacement);
      String decoded = secret.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
              .replace("&apos;", "'").replace("&amp;", "&");
      text = text.replace(decoded, replacement);
    }
    return text.getBytes(StandardCharsets.UTF_8);
  }

  public Object body(byte[] bytes, String contentType) {
    if (bytes.length == 0) return Map.of("empty", true);
    if (bytes.length > maxBodyBytes) return Map.of("omitted", "max-body-bytes", "limit", maxBodyBytes);
    if (contentType == null) return Map.of("omitted", "unknown-content-type");
    try {
      if (contentType.contains("json")) return sanitize(JSON.readValue(bytes, Object.class));
      if (contentType.contains("xml")) {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
          @Override
          public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException {
            throw error;
          }
        });
        return sanitize(xml(builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement(), 0));
      }
    } catch (Exception ignored) {
      return Map.of("omitted", "unparseable-body");
    }
    return Map.of("omitted", "unsupported-content-type");
  }

  private Object xml(org.w3c.dom.Element element, int depth) {
    if (depth > 32) return replacement;
    Map<String, Object> result = new LinkedHashMap<>();
    var children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++)
      if (children.item(i) instanceof org.w3c.dom.Element child) {
        String name = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
        Object value = xml(child, depth + 1);
        Object previous = result.putIfAbsent(name, value);
        if (previous != null) {
          if (previous instanceof List<?> list) {
            var copy = new ArrayList<Object>(list);
            copy.add(value);
            result.put(name, copy);
          } else result.put(name, new ArrayList<>(List.of(previous, value)));
        }
      }
    return result.isEmpty() ? element.getTextContent() : result;
  }

  public Object sanitize(Object value) {
    return sanitize(value, 0);
  }

  private Object sanitize(Object value, int depth) {
    if (depth > 32) return replacement;
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      map.forEach((key, item) -> result.put(String.valueOf(key), field(String.valueOf(key), item, depth)));
      return result;
    }
    if (value instanceof Collection<?> list) return list.stream().map(item -> sanitize(item, depth + 1)).toList();
    if (value instanceof String text) {
      return Pattern.compile("(?<![0-9])[0-9]{11}(?![0-9])").matcher(text)
              .replaceAll(match -> java.util.regex.Matcher.quoteReplacement(String.valueOf(field("documentNumber", match.group(), depth + 1))));
    }
    if (value == null || value instanceof Number || value instanceof Boolean) return value;
    return replacement;
  }

  private Object field(String name, Object value, int depth) {
    if (depth > 32) return replacement;
    String normalized = name.replaceAll("[-_]", "").toLowerCase(Locale.ROOT);
    if (Set.of("applicationresponse", "content", "contentfile").contains(normalized)) {
      if (value instanceof String encoded) return archive(encoded);
      if (value instanceof Map<?, ?> map && map.containsKey("decodedXml")) return sanitize(value, depth + 1);
    }
    if (SECRETS.contains(normalized) || normalized.contains("password")
            || normalized.endsWith("token") || normalized.endsWith("username")) return replacement;
    if (value instanceof Collection<?> list) return list.stream().map(item -> field(name, item, depth + 1)).toList();
    for (Rule rule : rules)
      if (rule.fields().stream().anyMatch(field -> field.equalsIgnoreCase(name))) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return switch (rule.mode()) {
          case "PARTIAL" -> text.length() <= (long) rule.keepPrefix() + rule.keepSuffix() ? replacement
                  : text.substring(0, rule.keepPrefix()) + replacement + text.substring(text.length() - rule.keepSuffix());
          case "REGEX" -> text.matches(rule.pattern())
                  ? text.replaceAll(rule.pattern(), Objects.requireNonNullElse(rule.replacement(), replacement)) : replacement;
          default -> replacement;
        };
      }
    if (Set.of("documentnumber", "phone", "email").contains(normalized)) return replacement;
    if (Set.of("instance", "path").contains(normalized) && value instanceof String path) return safePath(path);
    // Identificadores XML equivalentes al RUC del JSON.
    if (Set.of("identificationid", "ruccomprobante").contains(normalized))
      return field("documentNumber", value, depth + 1);
    return sanitize(value, depth + 1);
  }

  private Object archive(String encoded) {
    int depth = archiveDepth.get();
    if (depth >= 2 || encoded.length() > maxBodyBytes) return Map.of("omitted", "archive-limit");
    archiveDepth.set(depth + 1);
    try {
      byte[] zipped = Base64.getMimeDecoder().decode(encoded);
      try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipped))) {
        var entry = zip.getNextEntry();
        if (entry == null || entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml"))
          return replacement;
        return Map.of("decodedXml", body(zip.readNBytes(maxBodyBytes + 1), "application/xml"));
      }
    } catch (IOException | IllegalArgumentException ignored) {
      return replacement;
    } finally {
      if (depth == 0) archiveDepth.remove();
      else archiveDepth.set(depth);
    }
  }

  private String safePath(String path) {
    String safe = path.replaceAll("(/public/documents/)[^/]+", "$1***");
    var matcher = Pattern.compile("(?<=/companies/)[^/]+").matcher(safe);
    if (matcher.find())
      safe = safe.substring(0, matcher.start()) + field("documentNumber", matcher.group(), 0) + safe.substring(matcher.end());
    return safe.length() > 1024 ? "omitted" : safe;
  }

  private Map<String, Object> headers(HttpServletRequest request) {
    Map<String, Object> values = new LinkedHashMap<>();
    Collections.list(request.getHeaderNames()).forEach(name -> values.put(name,
            name.equalsIgnoreCase(HEADER) ? MDC.get("requestId") : Collections.list(request.getHeaders(name))));
    return values;
  }

  private Map<String, Object> responseHeaders(HttpServletResponse response) {
    Map<String, Object> values = new LinkedHashMap<>();
    response.getHeaderNames().forEach(name -> values.put(name,
            response.getHeaders(name)));
    return values;
  }

  public record Rule(List<String> fields, String mode, int keepPrefix, int keepSuffix,
                     String pattern, String replacement) {
  }

  /**
   * Captura un prefijo acotado sin retener ni alterar descargas completas.
   */
  private static final class CaptureResponse extends HttpServletResponseWrapper {
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final int limit;
    private ServletOutputStream stream;
    private PrintWriter writer;

    CaptureResponse(HttpServletResponse response, int limit) {
      super(response);
      this.limit = limit;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
      if (stream == null) {
        var delegate = super.getOutputStream();
        stream = new ServletOutputStream() {
          @Override
          public boolean isReady() {
            return delegate.isReady();
          }

          @Override
          public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
          }

          @Override
          public void write(int value) throws IOException {
            delegate.write(value);
            if (captured.size() < limit) captured.write(value);
          }

          @Override
          public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            captured.write(bytes, offset, Math.min(length, limit - captured.size()));
          }

          @Override
          public void flush() throws IOException {
            delegate.flush();
          }
        };
      }
      return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
      if (writer == null) writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getCharacterEncoding()));
      return writer;
    }

    @Override
    public void resetBuffer() {
      super.resetBuffer();
      captured.reset();
    }

    @Override
    public void reset() {
      super.reset();
      captured.reset();
    }

    @Override
    public void flushBuffer() throws IOException {
      finish();
      super.flushBuffer();
    }

    void finish() {
      if (writer != null) writer.flush();
    }
  }
}
