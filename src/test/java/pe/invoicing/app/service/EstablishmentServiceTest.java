package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.company.models.Address;
import pe.invoicing.app.service.document.models.Establishment;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EstablishmentServiceTest extends AbstractServiceTest {
  private Establishment local(String code) {
    return new Establishment(code, "Local " + code,
            Address.builder().street("Avenida del local " + code).geographicCode("150101")
                    .department("LIMA").province("LIMA").district("LIMA").countryCode("PE").build());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0000", "0001", "0011", "9999"})
  @DisplayName("Emitir con dirección y código real de establecimiento")
  void whenEstablishmentProvidedThenSnapshotInXmlAndDocument(String code) {
    var draft = draft("invoices/cash/request.json").toBuilder().establishment(local(code)).build();
    var result = documents.issueNewDocument(COMPANY, "local-emission-" + code, draft);
    var document = saved.get(result.getDocumentId());
    String xml = new String(files.get(document.getId() + ":XML"), StandardCharsets.UTF_8);
    assertThat(xml).contains(">" + code + "</cbc:AddressTypeCode>", "Avenida del local " + code);
    assertThat(document.getIssuerFiscalAddress()).isEqualTo("Avenida del local " + code);
    // Persistencia JSON ya existente: el local histórico no depende de la configuración actual.
    var restored = JSON.readValue(JSON.writeValueAsString(document), pe.invoicing.app.service.document.models.DocumentData.class);
    assertThat(restored.getEstablishment().code()).isEqualTo(code);
    assertThat(restored.getEstablishment().address().getStreet()).isEqualTo(document.getIssuerFiscalAddress());
  }

  @Test
  @DisplayName("Sin límite funcional de diez sucursales")
  void whenElevenEstablishmentsThenIssueFromEveryOne() {
    for (int i = 1; i <= 11; i++) {
      String code = String.format("%04d", i);
      documents.issueNewDocument(COMPANY, "local-unlimited-" + code,
              draft("invoices/cash/request.json").toBuilder().series(String.format("F%03d", i))
                      .establishment(local(code)).build());
    }
    assertThat(saved).hasSize(11);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "ABCDE", "-001", "10000"})
  @DisplayName("Código de establecimiento inválido no consume numeración")
  void whenInvalidCodeThenRejectBeforeReservation(String code) {
    assertThatThrownBy(() -> documents.issueNewDocument(COMPANY, "invalid-local-code",
            draft("invoices/cash/request.json").toBuilder().establishment(local(code)).build()))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Compatibilidad con comprobante sin establecimiento explícito")
  void whenNoLocalThenUseMainEstablishment() {
    var document = issue("invoices/cash/request.json", "default-establishment");
    assertThat(new String(files.get(document.getId() + ":XML"), StandardCharsets.UTF_8))
            .contains(">0000</cbc:AddressTypeCode>");
  }
}
