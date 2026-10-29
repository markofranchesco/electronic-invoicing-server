package pe.invoicing.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.company.models.BusinessLogo;
import pe.invoicing.app.service.company.models.Company;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessLogoServiceTest extends AbstractServiceTest {
  private String logo() throws Exception {
    return Base64.getEncoder().encodeToString(new org.springframework.core.io.ClassPathResource("fixtures/company/logo.png").getContentAsByteArray());
  }

  @Test @DisplayName("Logo de empresa se conserva en venta y se imprime proporcionalmente")
  void logoSnapshotAndTicket() throws Exception {
    String original = logo();
    var company = Company.builder().id(ACCOUNT).accountId(ACCOUNT).documentNumber(COMPANY)
            .legalName("POLLERIA MI NEGOCIO").logoBase64(original).address(companyData.address()).build();
    when(companyStore.getCompany(ACCOUNT,COMPANY)).thenReturn(company);
    var document = issue("receipts/cash/request.json","logo-snapshot-sale");
    company.setLogoBase64(null);
    assertThat(document.getIssuerLogoBase64()).isEqualTo(original);
    byte[] bytes = documents.downloadPdf(COMPANY,document.getId(),"TICKET_80");
    try (var pdf = Loader.loadPDF(bytes)) {
      assertThat(pdf.getPage(0).getMediaBox().getWidth()).isCloseTo(226.77f,within(0.1f));
      boolean image = false;
      for (var name : pdf.getPage(0).getResources().getXObjectNames())
        image |= pdf.getPage(0).getResources().getXObject(name) instanceof PDImageXObject;
      assertThat(image).isTrue();
      assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains("POLLERIA MI NEGOCIO", "Fábrica Abstracta").doesNotContain("Resumen de firma:", "Estado:");
    }
    if (Boolean.getBoolean("pdf.visual.sample")) {
      Files.createDirectories(Path.of("target/pdf-samples"));
      Files.write(Path.of("target/pdf-samples/ticket-logo.pdf"),bytes);
    }
  }

  @ParameterizedTest @ValueSource(strings={"not-base64!","PHN2Zz48L3N2Zz4=","data:image/png;base64,AA==","AA=="})
  @DisplayName("Logo inválido se rechaza antes de persistir")
  void malformedLogo(String value) {
    assertThatThrownBy(() -> BusinessLogo.validate(value)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test @DisplayName("Logo opcional y tamaño acotado")
  void optionalAndBounded() throws Exception {
    assertThat(BusinessLogo.validate(null)).isNull();
    assertThat(BusinessLogo.validate("")).isNull();
    assertThat(BusinessLogo.validate(logo())).isNotEmpty();
    assertThatThrownBy(() -> BusinessLogo.validate("A".repeat(699053))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    var bytes = new java.io.ByteArrayOutputStream();
    try (var stream = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
      javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2049,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",stream);
    }
    assertThatThrownBy(() -> BusinessLogo.validate(Base64.getEncoder().encodeToString(bytes.toByteArray())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }
}
