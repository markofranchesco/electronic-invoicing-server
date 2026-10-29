package pe.invoicing.app.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.config.sunat.credentials.SunatEmissionContext;
import pe.invoicing.app.config.sunat.properties.SunatProperties;
import pe.invoicing.app.config.sunat.soap.SunatSoapGateway;
import pe.invoicing.app.config.sunat.xml.SunatXml;
import pe.invoicing.app.config.sunat.xml.UblSchemaValidator;
import pe.invoicing.app.service.document.models.SunatFaultException;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SunatSoapGatewayTest extends AbstractServiceTest {
  @ParameterizedTest(name = "SOAP {0}: {1}")
  @CsvSource({"0102,SEND_FAILED", "1033,UNKNOWN", "2223,UNKNOWN", "2324,UNKNOWN", "2335,UNKNOWN", "Server,UNKNOWN"})
  @DisplayName("Clasificar error SOAP")
  void whenSoapFaultThenPreserveCodeAndFiscalOutcome(String code, String outcome) throws Exception {
    // Un duplicado o una caída de SUNAT no equivale a rechazo tributario.
    withResponse("<s:Fault><faultcode>s:Client." + code + "</faultcode><faultstring>Error SUNAT</faultstring></s:Fault>",
            client -> assertThatThrownBy(() -> client.sendSummary(COMPANY, "RC-20261026-1", "<SummaryDocuments xmlns=\"urn:sunat:names:specification:ubl:peru:schema:xsd:SummaryDocuments-1\"><ID xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">RC-20261026-1</ID></SummaryDocuments>".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOfSatisfying(SunatFaultException.class, failure -> {
                      assertThat(failure.code()).isEqualTo(code);
                      assertThat(failure.documentStatus()).isEqualTo(outcome);
                    }));
  }

  @Test
  @DisplayName("CDR de otra referencia")
  void whenTicketContainsAnotherReferenceThenRejectCdr() throws Exception {
    // Un ticket ajeno nunca puede aceptar la comunicación pendiente.
    String content = cdr("RC-20261026-99", COMPANY);
    withResponse("<getStatusResponse><statusCode>0</statusCode><content>" + content + "</content></getStatusResponse>",
            client -> assertThatThrownBy(() -> client.getSummaryStatus(COMPANY, "ticket", "RC-20261026-1"))
                    .isInstanceOf(ResponseStatusException.class));
  }

  @Test
  @DisplayName("CDR de otro emisor")
  void whenCdrBelongsToAnotherTaxpayerThenReject() throws Exception {
    // Coincidir en la serie no basta para asociar una constancia.
    String content = cdr("RC-20261026-1", "20999999999");
    withResponse("<getStatusResponse><statusCode>0</statusCode><content>" + content + "</content></getStatusResponse>",
            client -> assertThatThrownBy(() -> client.getSummaryStatus(COMPANY, "ticket", "RC-20261026-1"))
                    .isInstanceOf(ResponseStatusException.class));
  }

  @Test
  @DisplayName("CDR correlacionada")
  void whenCdrMatchesSummaryThenReadFiscalResult() throws Exception {
    // Se comprueba el adaptador real con SOAP y ZIP, sin sustituir el parser.
    String content = cdr("RC-20261026-1", COMPANY);
    withResponse("<getStatusResponse><statusCode>0</statusCode><content>" + content + "</content></getStatusResponse>",
            client -> assertThat(client.getSummaryStatus(COMPANY, "ticket", "RC-20261026-1")
                    .receipt().orElseThrow().accepted()).isTrue());
  }

  @ParameterizedTest(name = "CDR ambiguo {0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"multiple", "reference", "code"})
  @DisplayName("Rechazar constancia ambigua")
  void whenCdrContainsContradictoryInformationThenReject(String scenario) throws Exception {
    // Una constancia no puede aceptarse leyendo únicamente el primer dato que coincide.
    byte[] original = Base64.getDecoder().decode(cdr("RC-20261026-1", COMPANY));
    String xml;
    try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original))) {
      input.getNextEntry();
      xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    if ("reference".equals(scenario)) xml = xml.replace("<ID>RC-20261026-1</ID>", "<ID>RC-20261026-99</ID>");
    if ("code".equals(scenario))
      xml = xml.replace("<ResponseCode>0</ResponseCode>", "<ResponseCode>???</ResponseCode>");
    var output = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("R-one.xml"));
      zip.write(xml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      if ("multiple".equals(scenario)) {
        zip.putNextEntry(new ZipEntry("R-two.xml"));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    String encoded = Base64.getEncoder().encodeToString(output.toByteArray());
    withResponse("<getStatusResponse><statusCode>0</statusCode><content>" + encoded + "</content></getStatusResponse>",
            client -> assertThatThrownBy(() -> client.getSummaryStatus(COMPANY, "ticket", "RC-20261026-1"))
                    .isInstanceOf(ResponseStatusException.class));
  }

  @Test
  @DisplayName("Conservar causa de CDR inválida")
  void whenCdrReferenceDiffersThenPreserveDiagnosticCause() throws Exception {
    String content = cdr("RC-20261026-99", COMPANY);
    withResponse("<getStatusResponse><statusCode>0</statusCode><content>" + content + "</content></getStatusResponse>",
            client -> assertThatThrownBy(() -> client.getSummaryStatus(COMPANY, "ticket", "RC-20261026-1"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                      assertThat(failure.getReason()).isEqualTo("La respuesta CDR no es válida; el resultado no está confirmado.");
                      assertThat(failure.getCause()).hasMessage("El CDR pertenece a otra comunicación");
                    }));
  }

  @Test
  @DisplayName("Leer receptor CDR según SUNAT")
  void whenOfficialReceiverPartyThenAcceptMatchingCdr() throws Exception {
    String content = officialCdr("RC-20261026-1", COMPANY);
    withResponse("<getStatusResponse><statusCode>0</statusCode><content>" + content + "</content></getStatusResponse>",
            client -> assertThat(client.getSummaryStatus(COMPANY, "ticket", "RC-20261026-1").receipt()).isPresent());
  }

  @Test
  @DisplayName("No consultar CDR contra beta de envío")
  void whenBetaConsultationThenFailBeforeCallingNetwork() throws Exception {
    var client = new SunatSoapGateway(new SunatXml(new UblSchemaValidator()),
            new SunatEmissionContext(authentication, companyStore, vault, CLOCK),
            new SunatProperties(URI.create("https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService"),
                    URI.create("https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService"), new MockEnvironment()),
            new pe.invoicing.app.config.observability.ApplicationTrace(new MockEnvironment()).sunatClient());
    var document = pe.invoicing.app.service.document.models.DocumentData.builder().documentNumber(COMPANY)
            .documentType("01").series("F001").number(1L).build();
    assertThatThrownBy(() -> client.consultBill(document)).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(422));
  }

  private String officialCdr(String reference, String receiver) throws Exception {
    byte[] original = Base64.getDecoder().decode(cdr(reference, receiver));
    String xml;
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original))) {
      zip.getNextEntry();
      xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
    }
    xml = xml.replace("<IdentificationID>", "<PartyIdentification><ID>")
            .replace("</IdentificationID>", "</ID></PartyIdentification>");
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("R-response.xml"));
      zip.write(xml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  private String cdr(String reference, String receiver) throws Exception {
    String xml = "<ApplicationResponse><ReceiverParty><PartyIdentification><ID>" + receiver
            + "</ID></PartyIdentification></ReceiverParty><DocumentResponse><Response><ReferenceID>" + reference
            + "</ReferenceID><ResponseCode>0</ResponseCode><Description>Aceptado</Description></Response>"
            + "<DocumentReference><ID>" + reference + "</ID></DocumentReference></DocumentResponse></ApplicationResponse>";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("R-response.xml"));
      zip.write(xml.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return Base64.getEncoder().encodeToString(output.toByteArray());
  }

  private void withResponse(String body, java.util.function.Consumer<SunatSoapGateway> action) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] response = ("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>"
              + body + "</s:Body></s:Envelope>").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(body.contains("Fault") ? 500 : 200, response.length);
      try (var stream = exchange.getResponseBody()) {
        stream.write(response);
      }
    });
    server.start();
    try {
      URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      action.accept(new SunatSoapGateway(new SunatXml(new UblSchemaValidator()),
              new SunatEmissionContext(authentication, companyStore, vault, CLOCK),
              new SunatProperties(uri, uri, new MockEnvironment()),
              new pe.invoicing.app.config.observability.ApplicationTrace(new MockEnvironment()).sunatClient()));
    } finally {
      server.stop(0);
    }
  }
}
