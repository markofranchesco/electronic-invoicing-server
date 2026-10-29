package pe.invoicing.app.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRepresentationServiceTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Estado {0}")
  @ValueSource(strings = {"UNKNOWN", "REJECTED", "CANCELLED"})
  @DisplayName("PDF con estado tributario")
  void whenDocumentNotAcceptedThenPdfDisplaysCurrentStatus(String status) throws Exception {
    // Tener XML firmado no permite mostrar un resultado fiscal que todavía no existe.
    var document = issue("invoices/cash/request.json", "representation-status");
    document.setStatus(status);
    try (var pdf = Loader.loadPDF(documents.downloadPdf(COMPANY, document.getId(), "A4"))) {
      assertThat(new PDFTextStripper().getText(pdf)).contains(switch (status) {
        case "REJECTED" -> "COMPROBANTE RECHAZADO";
        case "CANCELLED" -> "COMPROBANTE ANULADO";
        case "SUBMITTED" -> "Enviado, pendiente de respuesta";
        default -> "EMISIÓN NO CONFIRMADA";
      });
    }
  }
}
