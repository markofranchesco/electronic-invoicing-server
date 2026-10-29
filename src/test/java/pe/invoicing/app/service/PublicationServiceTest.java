package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.document.impl.PublicationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PublicationServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Publicar originales al adquirente")
  void whenPublishedThenReadOriginalXmlWithDocumentSpecificKey() {
    // La clave identifica una publicación concreta y no permite navegar por otros comprobantes.
    var document = issue("invoices/cash/request.json", "publish-receipt");
    var service = new PublicationService(store, artifacts, authentication, new pe.invoicing.app.service.document.impl.DocumentPdfRenderer());
    var publication = service.publish(COMPANY, document.getId());
    assertThat(publication.key()).isNotBlank();
    assertThat(service.download(publication.key(), "XML").content()).isEqualTo(files.get(document.getId() + ":XML"));
    assertThat(document.getGrantedAt()).isNotNull();
    assertThat(service.publish(COMPANY, document.getId()).key()).isEqualTo(publication.key());
  }

  @Test
  @DisplayName("No publicar archivo ausente")
  void whenOriginalXmlMissingThenDoNotGrantDocument() {
    // Un estado aceptado sin su archivo no se presenta como una entrega exitosa.
    var document = issue("invoices/cash/request.json", "publish-missing");
    files.remove(document.getId() + ":XML");
    var service = new PublicationService(store, artifacts, authentication, new pe.invoicing.app.service.document.impl.DocumentPdfRenderer());
    assertThatThrownBy(() -> service.publish(COMPANY, document.getId())).isInstanceOf(ResponseStatusException.class);
    assertThat(document.getGrantedAt()).isNull();
    verify(store, never()).publishDocument(any(), anyString(), any(), anyString());
  }

  @Test
  @DisplayName("Publicación desconocida")
  void whenPublicationKeyUnknownThenDoNotReadAnyArtifact() {
    // Una clave inexistente no se transforma en una búsqueda por empresa o documento.
    var service = new PublicationService(store, artifacts, authentication, new pe.invoicing.app.service.document.impl.DocumentPdfRenderer());
    assertThatThrownBy(() -> service.download("a".repeat(32), "XML")).isInstanceOf(ResponseStatusException.class);
    verify(artifacts, never()).findArtifact(any(), anyString(), any(), anyString());
  }

  @Test
  @DisplayName("Publicación con constancia pendiente")
  void whenPublishedReceiptPendingThenExposeXmlWithoutInventingCdr() {
    // El adquirente dispone del ejemplar y la falta de CDR sigue siendo visible.
    var document = issue("receipts/cash/request.json", "publish-pending");
    var service = new PublicationService(store, artifacts, authentication, new pe.invoicing.app.service.document.impl.DocumentPdfRenderer());
    var publication = service.publish(COMPANY, document.getId());
    assertThat(service.page(publication.key())).contains("/public/documents/" + publication.key() + "/XML");
    assertThat(service.download(publication.key(), "XML").content()).isNotEmpty();
    assertThatThrownBy(() -> service.download(publication.key(), "CDR")).isInstanceOf(ResponseStatusException.class);
    verify(gateway, times(1)).sendSummary(anyString(), anyString(), any());
  }
}
