package pe.invoicing.app.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import pe.invoicing.app.config.sunat.credentials.SunatEmissionContext;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentDraft;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XmlAndPdfServiceTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Documento {0}")
  @ValueSource(
          strings = {
                  "invoices/cash/request.json",
                  "invoices/installments/request.json",
                  "receipts/cash/request.json"
          })
  @DisplayName("Firma XML verificable")
  void whenDocumentSignedThenVerifySignatureAndRejectTampering(String fixture) throws Exception {
    // Se verifica la firma criptográfica; no basta con encontrar una etiqueta Signature.
    DocumentData result = issue(fixture, "signed-document");
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    Document xml =
            factory
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(files.get(result.getId() + ":XML")));
    var publicKey =
            new SunatEmissionContext(authentication, companyStore, vault, CLOCK)
                    .signingMaterial(COMPANY)
                    .certificate()
                    .getPublicKey();
    var signature =
            xml.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature").item(0);
    DOMValidateContext original = new DOMValidateContext(publicKey, signature);
    assertThat(
            XMLSignatureFactory.getInstance("DOM")
                    .unmarshalXMLSignature(original)
                    .validate(original))
            .isTrue();
    xml.getElementsByTagNameNS("*", "PayableAmount").item(0).setTextContent("999999.00");
    DOMValidateContext tampered = new DOMValidateContext(publicKey, signature);
    assertThat(
            XMLSignatureFactory.getInstance("DOM")
                    .unmarshalXMLSignature(tampered)
                    .validate(tampered))
            .isFalse();
  }

  @Test
  @DisplayName("Descuento coherente")
  void whenDiscountAppliedThenXmlAndTotalsAgree() {
    // La base, el IGV y el descuento se verifican con cifras esperadas independientes.
    DocumentDraft base = draft("invoices/cash/request.json");
    DocumentData result =
            saved.get(
                    documents
                            .issueNewDocument(
                                    COMPANY,
                                    "discounted-sale",
                                    base.toBuilder()
                                            .items(
                                                    List.of(
                                                            base.items().getFirst().toBuilder()
                                                                    .discountAmount("11.80")
                                                                    .build()))
                                            .build())
                            .getDocumentId());
    assertThat(result.getTotals().getTotalAmount()).isEqualTo("106.20");
    assertThat(result.getTotals().getTaxableAmount()).isEqualTo("90.00");
    assertThat(result.getTotals().getIgvAmount()).isEqualTo("16.20");
    assertThat(new String(files.get(result.getId() + ":XML")))
            .contains("AllowanceCharge", ">106.20<", ">16.20<");
  }

  @Test
  @DisplayName("PDF con importes reales")
  void whenPdfRenderedThenContainNumberAndTotal() throws Exception {
    // Se extrae el texto del PDF real para comprobar sus datos de negocio.
    DocumentData result = issue("invoices/cash/request.json", "pdf-content");
    try (var pdf = Loader.loadPDF(documents.downloadPdf(COMPANY, result.getId(), "A4"))) {
      assertThat(new PDFTextStripper().getText(pdf))
              .contains("F001-00000001", "118.00", "18.00", COMPANY);
    }
  }
}
