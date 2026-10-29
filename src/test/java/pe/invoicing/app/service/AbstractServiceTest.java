package pe.invoicing.app.service;

import org.junit.jupiter.api.BeforeEach;
import org.mapstruct.factory.Mappers;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import pe.invoicing.app.config.security.certificate.Pkcs12CertificateInspector;
import pe.invoicing.app.config.security.crypto.Crypto;
import pe.invoicing.app.config.security.encryption.Vault;
import pe.invoicing.app.config.sunat.credentials.SunatEmissionContext;
import pe.invoicing.app.config.sunat.properties.SunatProperties;
import pe.invoicing.app.config.sunat.soap.SunatSoapGateway;
import pe.invoicing.app.config.sunat.xml.SunatXml;
import pe.invoicing.app.config.sunat.xml.UblSchemaValidator;
import pe.invoicing.app.controller.company.dtos.requests.CompanyUpsertRequest;
import pe.invoicing.app.controller.company.mappers.CompanyMapper;
import pe.invoicing.app.controller.document.dtos.requests.DocumentUpsertRequest;
import pe.invoicing.app.controller.document.mappers.DocumentMapper;
import pe.invoicing.app.service.authentication.impl.AutenticacionServiceImpl;
import pe.invoicing.app.service.authentication.port.IdentityStore;
import pe.invoicing.app.service.cancellation.impl.CancellationServiceImpl;
import pe.invoicing.app.service.company.impl.CompanyServiceImpl;
import pe.invoicing.app.service.company.models.CertificateBundle;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.company.models.CompanyData;
import pe.invoicing.app.service.company.models.SequenceInfo;
import pe.invoicing.app.service.company.port.CompanyStore;
import pe.invoicing.app.service.company.port.SeriesStore;
import pe.invoicing.app.service.creditnote.impl.CreditNoteServiceImpl;
import pe.invoicing.app.service.document.impl.DocumentPdfRenderer;
import pe.invoicing.app.service.document.impl.DocumentServiceImpl;
import pe.invoicing.app.service.document.models.DocumentArtifactData;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentDraft;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;
import pe.invoicing.app.service.document.port.DocumentArtifactStore;
import pe.invoicing.app.service.document.port.DocumentStore;
import pe.invoicing.app.service.session.impl.SessionServiceImpl;
import pe.invoicing.app.service.session.models.AuthPrincipal;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

public abstract class AbstractServiceTest {
  private static final UblSchemaValidator UBL = schemaValidator();

  private static UblSchemaValidator schemaValidator() {
    try {
      return new UblSchemaValidator();
    } catch (Exception exception) {
      throw new IllegalStateException("No se pudieron cargar los XSD de las pruebas.", exception);
    }
  }

  protected static final String COMPANY = "20123456786";
  protected static final UUID ACCOUNT = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  protected static final Clock CLOCK =
          Clock.fixed(Instant.parse("2026-10-27T02:00:00Z"), ZoneId.of("America/Lima"));
  protected static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  protected final DocumentMapper mapper = Mappers.getMapper(DocumentMapper.class);
  protected final Crypto crypto = new Crypto("service-tests-secret-2026-not-production", CLOCK);
  protected final IdentityStore identities = mock(IdentityStore.class);
  protected final CompanyStore companyStore = mock(CompanyStore.class);
  protected final SeriesStore series = mock(SeriesStore.class);
  protected final DocumentStore store = mock(DocumentStore.class);
  protected final DocumentArtifactStore artifacts = mock(DocumentArtifactStore.class);
  protected final Map<UUID, DocumentData> saved = new HashMap<>();
  protected final Map<String, byte[]> files = new HashMap<>();
  protected AuthPrincipal principal = new AuthPrincipal(UUID.randomUUID(), ACCOUNT);
  protected AutenticacionServiceImpl authentication;
  protected SessionServiceImpl sessions;
  protected CompanyServiceImpl companies;
  protected DocumentServiceImpl documents;
  protected CreditNoteServiceImpl notes;
  protected CancellationServiceImpl cancellations;
  protected SunatSoapGateway gateway;
  protected CompanyData companyData;
  protected Vault vault;

  protected <T> T fixture(String path, Class<T> type) {
    try (var input = new ClassPathResource(path).getInputStream()) {
      return JSON.readValue(input, type);
    } catch (IOException exception) {
      throw new IllegalStateException("Fixture ilegible: " + path, exception);
    }
  }

  protected DocumentDraft draft(String path) {
    return mapper.toService(fixture(path, DocumentUpsertRequest.class));
  }

  @BeforeEach
  void prepareServices() throws Exception {
    authentication = new AutenticacionServiceImpl(crypto, CLOCK, identities, () -> principal);
    sessions = new SessionServiceImpl(identities, () -> principal);
    vault =
            new Vault(
                    new MockEnvironment()
                            .withProperty("app.security.secret", "service-tests-secret-2026-not-production"));
    SunatProperties properties =
            new SunatProperties(
                    URI.create("http://localhost/unused"),
                    URI.create("http://localhost/unused"),
                    new MockEnvironment());
    companies =
            new CompanyServiceImpl(
                    authentication,
                    companyStore,
                    new Pkcs12CertificateInspector(CLOCK),
                    vault,
                    series,
                    CLOCK,
                    properties);
    companyData =
            Mappers.getMapper(CompanyMapper.class)
                    .toService(fixture("companies/valid/request.json", CompanyUpsertRequest.class));
    when(companyStore.getCompany(ACCOUNT, COMPANY))
            .thenAnswer(
                    call ->
                            Company.builder()
                                    .id(UUID.randomUUID())
                                    .accountId(ACCOUNT)
                                    .documentNumber(COMPANY)
                                    .legalName(companyData.legalName())
                                    .address(companyData.address())
                                    .build());
    when(companyStore.findEncryptedCredentials(ACCOUNT, COMPANY))
            .thenReturn(Optional.of(vault.encrypt("MODDATOS:MODDATOS")));
    when(companyStore.findEncryptedCertificate(ACCOUNT, COMPANY))
            .thenReturn(
                    Optional.of(
                            vault.encrypt(
                                    new CertificateBundle(
                                            companyData.certificate(), companyData.certificatePassword())
                                            .serialize())));
    when(series.listSequences(ACCOUNT, COMPANY))
            .thenReturn(List.of(SequenceInfo.builder().build()));
    gateway =
            spy(
                    new SunatSoapGateway(
                            new SunatXml(UBL),
                            new SunatEmissionContext(authentication, companyStore, vault, CLOCK),
                            properties, new pe.invoicing.app.config.observability.ApplicationTrace(new MockEnvironment()).sunatClient()));
    doReturn(fixture("sunat/accepted/response.json", SunatSendResult.class))
            .when(gateway)
            .sendBill(any(), any());
    doReturn("ticket-1").when(gateway).sendSummary(anyString(), anyString(), any());
    doReturn(new SunatStatus("98", Optional.empty()))
            .when(gateway)
            .getSummaryStatus(anyString(), anyString(), anyString());
    doReturn(new SunatStatus("98", Optional.empty())).when(gateway).consultBill(any());
    when(store.createIssuance(eq(ACCOUNT), any(), anyString(), anyString()))
            .thenAnswer(
                    call -> {
                      DocumentData document = call.getArgument(1);
                      document.setNumber((long) saved.size() + 1);
                      saved.put(document.getId(), document);
                      return document;
                    });
    when(store.claimEmission(eq(ACCOUNT), eq(COMPANY), any()))
            .thenAnswer(
                    call -> {
                      DocumentData document = saved.get(call.getArgument(2));
                      document.setStatus("PREPARING");
                      document.getCommunication().setBatchReference("RC-20261026-1");
                      document.getCommunication().setLastAttemptAt(OffsetDateTime.now(CLOCK));
                      return Optional.of(document);
                    });
    when(store.findDocument(eq(ACCOUNT), eq(COMPANY), any()))
            .thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(2))).orElseThrow());
    when(store.saveDocument(eq(ACCOUNT), any()))
            .thenAnswer(
                    call -> {
                      DocumentData document = call.getArgument(1);
                      saved.put(document.getId(), document);
                      return document;
                    });
    when(store.saveWithArtifact(eq(ACCOUNT), any(), anyString(), any()))
            .thenAnswer(
                    call -> {
                      DocumentData document = call.getArgument(1);
                      files.put(document.getId() + ":" + call.getArgument(2), call.getArgument(3));
                      saved.put(document.getId(), document);
                      return document;
                    });
    doAnswer(
            call -> {
              var artifact = (DocumentArtifactData) call.getArgument(0);
              files.put(artifact.documentId() + ":" + artifact.artifactType(), artifact.content());
              return null;
            })
            .when(artifacts)
            .saveArtifact(any());
    when(artifacts.findArtifact(eq(ACCOUNT), eq(COMPANY), any(), anyString()))
            .thenAnswer(
                    call ->
                            Optional.ofNullable(files.get(call.getArgument(2) + ":" + call.getArgument(3))));
    when(store.claimCancellation(eq(ACCOUNT), eq(COMPANY), any(), any()))
            .thenAnswer(
                    call -> {
                      DocumentData document = saved.get(call.getArgument(2));
                      var previousCancellation = document.getCancellation();
                      boolean repeated = previousCancellation != null;
                      document.setCancellation(call.getArgument(3));
                      document
                              .getCancellation()
                              .setReference(
                                      (document.getSeries().startsWith("B") ? "RC" : "RA") + (repeated ? "-20261026-3" : "-20261026-2"));
                      if (previousCancellation != null && "PREPARATION_FAILED".equals(previousCancellation.getStatus())) {
                        document.getCancellation().setId(previousCancellation.getId());
                        document.getCancellation().setReference(previousCancellation.getReference());
                        document.getCancellation().setRequestedAt(previousCancellation.getRequestedAt());
                      }
                      return Optional.of(document);
                    });
    when(store.publishDocument(eq(ACCOUNT), eq(COMPANY), any(), anyString())).thenAnswer(call -> {
      DocumentData document = saved.get(call.getArgument(2));
      document.setPublicationKey(call.getArgument(3));
      document.setGrantedAt(OffsetDateTime.now(CLOCK));
      return document;
    });
    when(store.findPublishedDocument(anyString())).thenAnswer(call -> saved.values().stream()
            .filter(d -> call.getArgument(0).equals(d.getPublicationKey())).findFirst()
            .map(d -> new pe.invoicing.app.service.document.models.PublishedDocument(ACCOUNT, d)));
    when(store.claimGrant(eq(ACCOUNT), eq(COMPANY), any()))
            .thenAnswer(call -> {
              DocumentData document = saved.get(call.getArgument(2));
              if (document.getGrantedAt() == null) document.setGrantedAt(OffsetDateTime.now(CLOCK));
              return document;
            });
    when(store.claimRecovery(eq(ACCOUNT), eq(COMPANY), any(), anyBoolean()))
            .thenAnswer(call -> Optional.of(saved.get(call.getArgument(2))));
    when(store.claimSummaryReport(eq(ACCOUNT), eq(COMPANY), any(), anyString()))
            .thenAnswer(call -> {
              DocumentData document = saved.get(call.getArgument(2));
              String previous = call.getArgument(3);
              var communication = document.getCommunication();
              if (previous.equals(communication.getPreviousReference())) return Optional.empty();
              files.put(document.getId() + ":SUMMARY_XML:" + previous, files.get(document.getId() + ":SUMMARY_XML"));
              communication.setPreviousReference(previous);
              communication.setPreviousDocumentStatus(document.getStatus());
              communication.setCondition(document.getStatus().startsWith("ACCEPTED") ? "2" : "1");
              communication.setBatchReference("RC-20261026-2");
              communication.setTicket(null);
              communication.setStatus("PREPARING");
              document.setStatus("SUMMARY_PREPARING");
              return Optional.of(document);
            });
    documents =
            new DocumentServiceImpl(
                    crypto,
                    CLOCK,
                    authentication,
                    companies,
                    store,
                    artifacts,
                    new DocumentPdfRenderer(),
                    gateway,
                    properties);
    notes = new CreditNoteServiceImpl(documents);
    cancellations =
            new CancellationServiceImpl(documents, store, companies, authentication, gateway, CLOCK, properties);
  }

  protected DocumentData issue(String path, String key) {
    return saved.get(documents.issueNewDocument(COMPANY, key, draft(path)).getDocumentId());
  }
}
