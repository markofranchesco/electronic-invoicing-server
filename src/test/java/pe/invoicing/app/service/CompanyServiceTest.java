package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.company.models.CompanyData;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyServiceTest extends AbstractServiceTest {
  @Test
  @DisplayName("Empresa lista")
  void whenCertificateCredentialsAndSeriesExistThenAllowIssuance() {
    // Certificado y cifrado reales, solo persistencia sustituida.
    assertThat(companies.getCompany(COMPANY).getReady()).isTrue();
  }

  @Test
  @DisplayName("Faltan credenciales")
  void whenCredentialsAbsentThenCompanyIsNotReady() {
    // Tener certificado no sustituye las credenciales SOL.
    when(companyStore.findEncryptedCredentials(ACCOUNT, COMPANY)).thenReturn(Optional.empty());
    assertThat(companies.getCompany(COMPANY).getReady()).isFalse();
    assertThatThrownBy(() -> issue("invoices/cash/request.json", "company-not-ready"))
            .isInstanceOf(ResponseStatusException.class);
    verify(store, never()).createIssuance(any(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Certificado inválido")
  void whenCertificateCannotBeDecodedThenRejectBeforeSaving() {
    // El inspector real debe impedir material criptográfico ilegible.
    assertThatThrownBy(
            () ->
                    companies.upsertCompany(
                            COMPANY, CompanyData.builder().certificate("not-base64!").build()))
            .isInstanceOf(ResponseStatusException.class);
    verify(companyStore, never()).saveCompany(any(), anyString(), any(), anyString(), anyString());
  }

  @Test
  @DisplayName("Certificado de otra empresa")
  void whenCertificateIdentifiesAnotherTaxpayerThenReject() {
    // El sujeto del certificado se contrasta antes de guardar secretos.
    assertThatThrownBy(() -> companies.upsertCompany("20999999991", companyData))
            .isInstanceOf(ResponseStatusException.class);
    verify(companyStore, never()).saveCompany(any(), anyString(), any(), anyString(), anyString());
  }
}
