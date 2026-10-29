package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.repository.artifact.DocumentArtifactEntityRepository;
import pe.invoicing.app.repository.artifact.IssueOperationEntityRepository;
import pe.invoicing.app.repository.artifact.impl.DocumentArtifactStoreImpl;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;
import pe.invoicing.app.service.document.models.DocumentArtifactData;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactStoreTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Histórico {0}")
  @ValueSource(strings = {"SUMMARY_XML:RC-20261026-1", "CDR:RC-20261026-1", "CANCELLATION_XML:RA-20261026-2", "CANCELLATION_CDR:RA-20261026-2"})
  @DisplayName("Conservar versiones tributarias")
  void whenCommunicationArchivedThenStoreByReference(String type) {
    // El adaptador debe aceptar históricos sin reemplazar el archivo de otra comunicación.
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var repository = mock(DocumentArtifactEntityRepository.class);
    var operations = mock(IssueOperationEntityRepository.class);
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(UUID.randomUUID());
    var adapter = new DocumentArtifactStoreImpl(taxpayers, repository, operations);
    assertThatCode(() -> adapter.saveArtifact(new DocumentArtifactData(ACCOUNT, COMPANY,
            UUID.randomUUID(), type, new byte[]{1, 2, 3}))).doesNotThrowAnyException();
    verify(repository).save(any());
  }

  @ParameterizedTest(name = "Original {0}")
  @ValueSource(strings = {"XML", "SIGNED_XML"})
  @DisplayName("XML original inmutable")
  void whenSignedOriginalExistsThenRejectDifferentBytes(String type) {
    // La regeneración no puede sustituir un comprobante que ya tiene una firma persistida.
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var repository = mock(DocumentArtifactEntityRepository.class);
    var operations = mock(IssueOperationEntityRepository.class);
    var document = UUID.randomUUID();
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    when(repository.findById(any())).thenReturn(java.util.Optional.of(
            pe.invoicing.app.repository.artifact.entity.DocumentArtifactEntity.builder()
                    .taxpayerId(ACCOUNT).documentId(document).content(new byte[]{1}).build()));
    assertThatThrownBy(() -> new DocumentArtifactStoreImpl(taxpayers, repository, operations)
            .saveArtifact(new DocumentArtifactData(ACCOUNT, COMPANY, document, type, new byte[]{2})))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verify(repository, never()).save(any());
  }
}
