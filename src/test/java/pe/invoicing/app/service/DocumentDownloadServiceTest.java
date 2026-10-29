package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DocumentDownloadServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Descargar XML firmado")
  void whenXmlDownloadedThenReturnOriginalBytesAndSunatFilename() {
    // Descargar no vuelve a firmar ni envía el comprobante otra vez.
    var document = issue("invoices/cash/request.json", "download-xml");
    byte[] signed = files.get(document.getId() + ":XML").clone();
    var download = documents.downloadFile(COMPANY, document.getId(), "XML");
    assertThat(download.content()).isEqualTo(signed);
    assertThat(download.filename()).isEqualTo(COMPANY + "-01-F001-1.xml");
    assertThat(download.contentType()).isEqualTo("application/xml");
    verify(gateway, times(1)).sendBill(any(), any());
  }

  @Test
  @DisplayName("Descargar CDR ZIP")
  void whenCdrDownloadedThenReturnStoredZip() {
    // La constancia se entrega tal como fue recibida de SUNAT.
    var document = issue("invoices/cash/request.json", "download-cdr");
    var download = documents.downloadFile(COMPANY, document.getId(), "CDR");
    assertThat(download.content()).isEqualTo(files.get(document.getId() + ":CDR"));
    assertThat(download.filename()).isEqualTo("R-" + COMPANY + "-01-F001-1.zip");
    assertThat(download.contentType()).isEqualTo("application/zip");
  }

  @Test
  @DisplayName("CDR aún pendiente")
  void whenCdrUnavailableThenReturnNotFoundWithoutGeneratingOne() {
    // Un ticket recibido no debe convertirse en una constancia ficticia.
    var document = issue("receipts/cash/request.json", "download-pending");
    assertThatThrownBy(() -> documents.downloadFile(COMPANY, document.getId(), "CDR"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
  }

  @Test
  @DisplayName("Disponibilidad al adquirente")
  void whenDocumentMadeAvailableThenRecordGrantAndRejectVoid() {
    // La puesta a disposición no cambia el XML y evita darlo de baja como no otorgado.
    var document = issue("invoices/cash/request.json", "grant-document");
    documents.markGranted(COMPANY, document.getId());
    assertThatThrownBy(() -> cancellations.requestCancellation(COMPANY, document.getId(),
            fixture("cancellations/invoice/request.json", pe.invoicing.app.service.cancellation.models.CancellationDetails.class)))
            .isInstanceOf(ResponseStatusException.class);
    verify(gateway, never()).sendSummary(anyString(), anyString(), any());
  }

  @Test
  @DisplayName("Archivo de otra empresa")
  void whenDocumentNotOwnedThenDoNotReadArtifact() {
    // La descarga mantiene el alcance de empresa y cuenta del servicio existente.
    var id = java.util.UUID.randomUUID();
    doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND))
            .when(store).findDocument(ACCOUNT, COMPANY, id);
    assertThatThrownBy(() -> documents.downloadFile(COMPANY, id, "XML"))
            .isInstanceOf(ResponseStatusException.class);
    verify(artifacts, never()).findArtifact(any(), anyString(), any(), anyString());
  }

  @Test
  @DisplayName("Estado operativo visible")
  void whenResponseMappedThenExposeReferenceNeededForRecovery() {
    // El cliente puede recuperar el ticket o rectificar el RC usando la respuesta de consulta.
    var document = issue("receipts/cash/request.json", "response-reference");
    var response = org.mapstruct.factory.Mappers.getMapper(
            pe.invoicing.app.controller.document.mappers.DocumentResponseMapper.class).toApi(document);
    assertThat(response.emissionStatus()).isEqualTo("SUBMITTED");
    assertThat(response.communicationReference()).isEqualTo("RC-20261026-1");
    assertThat(response.communicationTicket()).isEqualTo("ticket-1");
  }
}
