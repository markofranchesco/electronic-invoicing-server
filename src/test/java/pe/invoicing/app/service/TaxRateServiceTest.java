package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.document.models.TaxRates;
import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaxRateServiceTest extends AbstractServiceTest {
  private void eligible() {
    Company company = companies.getCompany(COMPANY);
    company.setSpecialIgvFrom(LocalDate.of(2026, 1, 1));
    company.setSpecialIgvUntil(LocalDate.of(2026, 12, 31));
    when(companyStore.getCompany(ACCOUNT, COMPANY)).thenReturn(company);
  }

  @Test @DisplayName("IGV especial en XML y nota")
  void whenEligibleRestaurantThenCalculateAndPreserveSpecialRate() {
    // Los mappers, cálculos, firma y validación XSD son reales; solo se simula SUNAT y persistencia.
    eligible();
    var original = saved.get(documents.issueNewDocument(COMPANY, "special-rate-2026",
            draft("tax/special-2026/request.json")).getDocumentId());
    assertThat(original.getTotals().getTaxableAmount()).isEqualTo("100.00");
    assertThat(original.getTotals().getIgvAmount()).isEqualTo("10.50");
    String xml = new String(files.get(original.getId() + ":XML"), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(xml).contains("<cbc:Percent>10.50</cbc:Percent>", ">110.50<");
    Company company = companies.getCompany(COMPANY);
    company.setSpecialIgvFrom(null);
    company.setSpecialIgvUntil(null);
    var note = saved.get(notes.issue(COMPANY, original.getId(), "special-rate-note",
            new CreditNoteDraft("06", "Devolución total", "FC01", original.getIssueDate(), null)).getDocumentId());
    assertThat(note.getTotals()).isEqualTo(original.getTotals());
    assertThat(note.getItems().getFirst().getIgvRate()).isEqualTo("10.50");
  }

  @Test @DisplayName("Elegibilidad obligatoria")
  void whenEligibilityMissingThenDoNotReserveOrSend() {
    // El giro del restaurante no habilita automáticamente la tasa especial.
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "missing-eligibility",
            draft("tax/special-2026/request.json"))).isInstanceOf(ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
    verify(gateway, never()).sendBill(any(), any());
  }

  @Test @DisplayName("Régimen general conservado")
  void whenEligibleCompanySelectsGeneralOperationThenKeepEighteenPercent() {
    // Las operaciones distintas a gastronomía pueden seguir siendo generales.
    eligible();
    var result = issue("invoices/cash/request.json", "general-unchanged");
    assertThat(result.getTotals().getIgvAmount()).isEqualTo("18.00");
  }

  @ParameterizedTest @CsvSource({"2025,10.00", "2026,10.50", "2027,15.00"})
  @DisplayName("Tasa por fecha")
  void whenSpecialRegimeThenResolveRateForIssueDate(int year, String rate) {
    // La tabla temporal incluye IPM; no se acepta una tasa enviada libremente por el cliente.
    var date = LocalDate.of(year, 12, 31);
    var company = Company.builder().specialIgvFrom(LocalDate.of(year, 1, 1)).specialIgvUntil(date).build();
    var result = TaxRates.resolve(draft("tax/special-2026/request.json").toBuilder().issueDate(date).build(), company);
    assertThat(result.items().getFirst().getIgvRate()).isEqualTo(rate);
    assertThatThrownBy(() -> TaxRates.resolve(draft("tax/special-2026/request.json")
            .toBuilder().issueDate(date.plusDays(1)).build(), company)).isInstanceOf(ResponseStatusException.class);
  }

  @Test @DisplayName("Sin tipo de cambio falso en PEN")
  void whenSolesHaveForeignExchangeRateThenReject() {
    // No permitir eludir el umbral de identificación ni alterar el RC.
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "invalid-pen-rate",
            draft("receipts/cash/request.json").toBuilder().exchangeRate("0.01").build()))
            .isInstanceOf(ResponseStatusException.class);
  }

  @Test @DisplayName("RC conserva moneda y centavos")
  void whenTinyForeignReceiptThenSummaryKeepsOriginalAmounts() {
    // Regresión del ejemplo USD 0.08 de la auditoría: 0.07 + 0.01 = 0.08.
    var request = draft("receipts/cash/request.json");
    var document = saved.get(documents.issueNewDocument(COMPANY, "tiny-usd-receipt", request.toBuilder()
            .currency("USD").exchangeRate("3.80").items(List.of(request.items().getFirst().toBuilder()
                    .quantity("1").unitPrice("0.08").build())).build()).getDocumentId());
    String rc = new String(files.get(document.getId() + ":SUMMARY_XML"), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(rc).contains("currencyID=\"USD\">0.08", "currencyID=\"USD\">0.07", "currencyID=\"USD\">0.01")
            .doesNotContain("currencyID=\"PEN\"", "<cbc:InstructionID>02</cbc:InstructionID>");
  }
  @Test @DisplayName("Compatibilidad de idempotencia")
  void whenOldRequestOmitsNewFieldsThenKeepItsHashPayload() {
    // Los campos nuevos nulos no deben modificar el JSON usado por claves de operaciones anteriores.
    String request = JSON.writeValueAsString(draft("invoices/cash/request.json"));
    assertThat(request).doesNotContain("taxRegime", "igvRate", "originalLineId");
  }

  @Test @DisplayName("Una tasa por comprobante")
  void whenStoredDocumentMixesIgvRatesThenRejectBeforeSigning() {
    // Regla 3462: mezclar 18% y 10.5% no se arregla agrupando subtotales.
    var document = issue("invoices/cash/request.json", "mixed-rates-source");
    var item = document.getItems().getFirst();
    document.setItems(List.of(item, item.toBuilder().igvRate("10.50").build()));
    assertThatThrownBy(() -> gateway.signedXml(document, companies.getCompany(COMPANY)))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("3462");
  }

}
