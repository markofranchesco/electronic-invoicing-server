package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;
import pe.invoicing.app.service.document.models.DocumentData;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TaxXmlServiceTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Comprobante {0}, afectación {1}")
  @CsvSource({"01,10,100.00,18.00,1000,IGV,VAT", "01,20,118.00,0.00,9997,EXO,VAT",
          "01,30,118.00,0.00,9998,INA,FRE", "03,10,100.00,18.00,1000,IGV,VAT",
          "03,20,118.00,0.00,9997,EXO,VAT", "03,30,118.00,0.00,9998,INA,FRE",
          "07,10,100.00,18.00,1000,IGV,VAT", "07,20,118.00,0.00,9997,EXO,VAT",
          "07,30,118.00,0.00,9998,INA,FRE"})
  @DisplayName("XML por afectación admitida")
  void whenSupportedTaxCombinationThenUseSunatCatalogAndExpectedAmounts(
          String type, String affectation, String base, String tax, String code, String name, String international) throws Exception {
    // Catálogos 05/07 SUNAT del 26.08.2026; cifras esperadas independientes del generador.
    var request = draft("03".equals(type) ? "receipts/cash/request.json" : "invoices/cash/request.json");
    request = request.toBuilder().items(List.of(request.items().getFirst().toBuilder()
            .quantity("1").unitPrice("118.00").taxAffectationCode(affectation).build())).build();
    DocumentData document = saved.get(documents.issueNewDocument(COMPANY, "matrix-" + type + affectation, request).getDocumentId());
    if ("07".equals(type))
      document = saved.get(notes.issue(COMPANY, document.getId(), "matrix-note-" + affectation,
              new CreditNoteDraft("06", "Devolución total", "FC01", request.issueDate(), null)).getDocumentId());
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(files.get(document.getId() + ":XML")));
    assertThat(xml.getElementsByTagNameNS("*", "UBLVersionID").item(0).getTextContent()).isEqualTo("2.1");
    assertThat(xml.getElementsByTagNameNS("*", "TaxableAmount").item(0).getTextContent()).isEqualTo(base);
    assertThat(xml.getElementsByTagNameNS("*", "TaxAmount").item(0).getTextContent()).isEqualTo(tax);
    var scheme = (org.w3c.dom.Element) xml.getElementsByTagNameNS("*", "TaxScheme").item(0);
    assertThat(scheme.getElementsByTagNameNS("*", "ID").item(0).getTextContent()).isEqualTo(code);
    assertThat(scheme.getElementsByTagNameNS("*", "Name").item(0).getTextContent()).isEqualTo(name);
    assertThat(scheme.getElementsByTagNameNS("*", "TaxTypeCode").item(0).getTextContent()).isEqualTo(international);
  }

  @Test
  @DisplayName("Totales inconsistentes")
  void whenTotalsDisagreeWithLinesThenRejectBeforeSigning() {
    // Un XML estructuralmente válido no debe ocultar totales incoherentes.
    DocumentData document = issue("invoices/cash/request.json", "inconsistent-totals");
    document.setTotals(document.getTotals().toBuilder().totalAmount("999.00").build());
    assertThatThrownBy(() -> gateway.signedXml(document, companies.getCompany(COMPANY)))
            .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  @DisplayName("Unidad fuera del catálogo")
  void whenUnitCodeUnknownThenRejectBeforeSending() {
    // Tres letras no acreditan pertenencia al catálogo 03.
    var request = draft("invoices/cash/request.json");
    var invalid = request.toBuilder().items(List.of(request.items().getFirst().toBuilder()
            .unitCode("XYZ").build())).build();
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "invalid-unit", invalid))
            .isInstanceOf(ResponseStatusException.class);
    verify(gateway, never()).sendBill(any(), any());
  }

  @Test
  @DisplayName("Boleta de setecientos soles")
  void whenReceiptExactlySevenHundredThenAllowUnidentifiedConsumer() {
    // La obligación por importe aplica cuando se supera 700, no cuando es igual.
    var request = draft("receipts/cash/request.json");
    var exact = request.toBuilder().customer(pe.invoicing.app.service.document.models.Customer.builder()
                    .documentType("0").documentNumber("0").name("CONSUMIDOR FINAL").build())
            .items(List.of(request.items().getFirst().toBuilder().quantity("1").unitPrice("700.00").build())).build();
    assertThat(documents.issueNewDocument(COMPANY, "exact-threshold", exact).getDocumentId()).isNotNull();
  }

  @ParameterizedTest(name = "Mixta {0} en {1}")
  @CsvSource({"01,PEN", "01,USD", "01,EUR", "03,PEN", "03,USD", "03,EUR"})
  @DisplayName("Mixta con descuento y nota")
  void whenMixedDiscountedSaleThenKeepTaxTotalsInXmlAndTotalCreditNote(String type, String currency) throws Exception {
    // La nota total y el RC deben conservar importes y referencias de la venta mixta.
    var request = draft("03".equals(type) ? "receipts/cash/request.json" : "invoices/cash/request.json");
    var item = request.items().getFirst();
    request = request.toBuilder().currency(currency).exchangeRate("PEN".equals(currency) ? null : "3.80")
            .items(List.of(item.toBuilder().quantity("1").unitPrice("118.00").discountAmount("11.80").taxAffectationCode("10").build(),
                    item.toBuilder().quantity("1").unitPrice("118.00").taxAffectationCode("20").build(),
                    item.toBuilder().quantity("1").unitPrice("118.00").taxAffectationCode("30").build())).build();
    var document = saved.get(documents.issueNewDocument(COMPANY, "mixed-" + type + currency, request).getDocumentId());
    if ("03".equals(type)) {
      doReturn(new pe.invoicing.app.service.document.models.SunatStatus("0", java.util.Optional.of(
              fixture("sunat/accepted/response.json", pe.invoicing.app.service.document.models.SunatSendResult.class))))
              .when(gateway).getSummaryStatus(anyString(), anyString(), anyString());
      document = documents.getDocument(COMPANY, document.getId());
      documents.markGranted(COMPANY, document.getId());
    }
    var note = saved.get(notes.issue(COMPANY, document.getId(), "mixed-note-" + type + currency,
            new CreditNoteDraft("06", "Devolución total", "03".equals(type) ? "BC01" : "FC01",
                    request.issueDate(), null)).getDocumentId());
    assertThat(note.getTotals().getTotalAmount()).isEqualTo("342.20");
    assertThat(note.getTotals().getIgvAmount()).isEqualTo("16.20");
    assertThat(note.getTotals()).isEqualTo(document.getTotals());
    String xml = new String(files.get(note.getId() + ":XML"), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(xml).contains("CreditNote", "BillingReference", "342.20", "16.20", "currencyID=\"" + currency + "\"");
    if ("03".equals(type)) {
      String summary = new String(files.get(note.getId() + ":SUMMARY_XML"), java.nio.charset.StandardCharsets.UTF_8);
      assertThat(summary).contains("<cbc:DocumentTypeCode>07</cbc:DocumentTypeCode>",
              "<cbc:DocumentTypeCode>03</cbc:DocumentTypeCode>", "currencyID=\"" + currency + "\"");
      assertThat(summary).contains(">342.20<", ">16.20<");
    }
  }
}
