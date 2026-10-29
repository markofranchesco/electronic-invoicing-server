package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import pe.invoicing.app.service.document.impl.*;
import pe.invoicing.app.controller.document.PublicationController;
import java.util.Optional;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReceiptPresentationServiceTest extends AbstractServiceTest {
  private DocumentPdfRenderer renderer() { return new DocumentPdfRenderer(new ReceiptPresentation("https://comprobantes.miempresa.test")); }

  @ParameterizedTest @ValueSource(strings={"TICKET_54","TICKET_80","A4"})
  void fiscalQrCanBeDecodedFromPrintedPdf(String format) throws Exception {
    var document = issue("receipts/cash/request.json","read-printed-qr");
    document.setEnvironment("PRODUCTION");
    document.setIssuerLegalName("POLLERÍA LA ESQUINA S.A.C.");
    document.setPublicationKey("a".repeat(32));
    var xml = files.get(document.getId()+":XML");
    byte[] bytes = renderer().render(COMPANY,document,format,Optional.of(xml));
    try(var pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf);
      assertThat(text).doesNotContain("Resumen de firma:", "Estado:", "pendiente de respuesta", "BETA", "DEMOSTRACIÓN")
              .contains("Fábrica Abstracta", "Consulte y descargue", "P. UNIT.", "TOTAL");
      if (!format.equals("A4")) assertThat(pdf.getPage(0).getMediaBox().getWidth())
              .isCloseTo(Float.parseFloat(format.substring(7))*72/25.4f,within(0.02f));
      var image = new PDFRenderer(pdf).renderImageWithDPI(0,200);
      var source = new RGBLuminanceSource(image.getWidth(),image.getHeight(),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()));
      var decoded = new com.google.zxing.multi.qrcode.QRCodeMultiReader()
              .decodeMultiple(new BinaryBitmap(new HybridBinarizer(source)), java.util.Map.of(DecodeHintType.TRY_HARDER, true));
      assertThat(decoded).hasSize(2);
      var qr = java.util.Arrays.stream(decoded).filter(value -> !value.getText().startsWith("https://")).findFirst().orElseThrow();
      var web = java.util.Arrays.stream(decoded).filter(value -> value.getText().startsWith("https://")).findFirst().orElseThrow();
      assertThat(web.getText()).isEqualTo("https://comprobantes.miempresa.test/public/documents/"+"a".repeat(32));
      var fields = qr.getText().split("\\|",-1);
      assertThat(fields).hasSize(10);
      assertThat(fields[0]).isEqualTo(COMPANY);
      assertThat(fields[1]).isEqualTo("03");
      assertThat(fields[2]).isEqualTo(document.getSeries());
      assertThat(fields[3]).isEqualTo(document.getNumber().toString());
      assertThat(fields[4]).isEqualTo("3.60"); assertThat(fields[5]).isEqualTo("23.60");
      assertThat(fields[6]).isEqualTo(document.getIssueDate().toString());
      assertThat(fields[7]).isEqualTo(document.getCustomer().getDocumentType());
      assertThat(fields[8]).isEqualTo(document.getCustomer().getDocumentNumber());
      var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
      var signed = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
      assertThat(fields[9]).isEqualTo(signed.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#","DigestValue").item(0).getTextContent());
      assertThat(qr.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL)).isEqualTo("Q");
      assertThat(pdf.getPage(0).getAnnotations()).anySatisfy(annotation -> {
        assertThat(annotation).isInstanceOf(org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink.class);
        var action = (org.apache.pdfbox.pdmodel.interactive.action.PDActionURI)((org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink)annotation).getAction();
        assertThat(action.getURI()).isEqualTo("https://comprobantes.miempresa.test/public/documents/"+"a".repeat(32));
      });
    }
    if (Boolean.getBoolean("pdf.visual.sample")) {
      Files.createDirectories(Path.of("target/pdf-samples"));
      Files.write(Path.of("target/pdf-samples/receipt-"+format+".pdf"),bytes);
    }
  }

  @Test void printedUrlRoutesToRealPageAndOriginalFiles() throws Exception {
    var document = issue("receipts/cash/request.json","real-public-page");
    var service = new PublicationService(store,artifacts,authentication,renderer());
    var publication = service.publish(COMPANY,document.getId());
    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new PublicationController(service)).build();
    mvc.perform(get(publication.path())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(document.getSeries()+"-"+document.getNumber())))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Enviado a SUNAT; respuesta en proceso")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ENTORNO DE PRUEBAS"))));
    mvc.perform(get(publication.path()+"/XML")).andExpect(status().isOk()).andExpect(content().bytes(files.get(document.getId()+":XML")));
    mvc.perform(get(publication.path()+"/PDF")).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    mvc.perform(get(publication.path()+"/CDR")).andExpect(status().isNotFound());
    mvc.perform(get(publication.path()+"/status")).andExpect(status().isOk())
            .andExpect(jsonPath("$.documentNumber").value(COMPANY))
            .andExpect(jsonPath("$.emissionStatus").value("SUBMITTED"))
            .andExpect(jsonPath("$.cdrAvailable").value(false))
            .andExpect(jsonPath("$.accountId").doesNotExist());
    mvc.perform(get("/public/documents/"+"f".repeat(32))).andExpect(status().isNotFound());
    verify(gateway,times(1)).sendSummary(any(),any(),any());
  }

  @Test void largeSaleKeepsAllLinesAndTotals() throws Exception {
    var draft = draft("receipts/cash/request.json");
    var items = java.util.stream.IntStream.rangeClosed(1,35).mapToObj(i -> draft.items().getFirst().toBuilder()
            .description("Producto " + i + " - presentación familiar").build()).toList();
    var result = documents.issueNewDocument(COMPANY,"large-ticket-35-lines",draft.toBuilder().items(items).build());
    var document = saved.get(result.getDocumentId()); document.setEnvironment("PRODUCTION");
    document.setIssuerLegalName("MINIMARKET LA ESQUINA S.A.C."); document.setPublicationKey("a".repeat(32));
    var bytes = renderer().render(COMPANY,document,"TICKET_80",Optional.of(files.get(document.getId()+":XML")));
    try(var pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ");
      for(int i=1;i<=35;i++) assertThat(text).contains("Producto "+i+" -");
      assertThat(text).contains("826.00");
      assertThat(pdf.getNumberOfPages()).isEqualTo(1);
    }
    if(Boolean.getBoolean("pdf.visual.sample")) Files.write(Path.of("target/pdf-samples/minimarket-35.pdf"),bytes);
  }

  @Test void environmentDoesNotAddPrintedWarningsAndUrlsAreValidated() throws Exception {
    assertThatThrownBy(() -> new ReceiptPresentation("javascript:alert(1)")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReceiptPresentation("https://user:pass@example.com")).isInstanceOf(IllegalArgumentException.class);
    assertThat(new ReceiptPresentation("").documentUrl("a".repeat(32))).isEmpty();
    var document = issue("receipts/cash/request.json","beta-remains-beta");
    try (var pdf = Loader.loadPDF(renderer().render(COMPANY,document,"TICKET_54",Optional.of(files.get(document.getId()+":XML"))))) {
      assertThat(new PDFTextStripper().getText(pdf).replaceAll("\\s+", " ")).doesNotContain("BETA", "SIN VALIDEZ TRIBUTARIA", "DESARROLLO");
    }
  }
}
