package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.repository.document.CounterEntityRepository;
import pe.invoicing.app.repository.document.entity.CounterEntity;
import pe.invoicing.app.repository.document.impl.SeriesStoreImpl;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeriesStoreTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Comunicación {0}")
  @ValueSource(strings = {"RA", "RC"})
  @DisplayName("Correlativo de cinco dígitos")
  void whenCommunicationExceedsFiveDigitsThenReject(String type) {
    // El límite del resumen no es el límite de ocho dígitos de una factura.
    CounterEntityRepository counters = mock(CounterEntityRepository.class);
    TaxpayerEntityRepository taxpayers = mock(TaxpayerEntityRepository.class);
    UUID taxpayer = UUID.randomUUID();
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(taxpayer);
    when(counters.lockById(any())).thenReturn(Optional.of(CounterEntity.builder()
            .taxpayerId(taxpayer).nextNumber(100000).build()));
    assertThatThrownBy(() -> new SeriesStoreImpl(taxpayers, counters, CLOCK)
            .allocateNextNumber(ACCOUNT, COMPANY, type, type + "01"))
            .isInstanceOf(ResponseStatusException.class);
    verify(counters, never()).saveAndFlush(any());
  }

  @ParameterizedTest(name = "Comprobante {0}")
  @ValueSource(strings = {"01", "03", "07"})
  @DisplayName("Correlativo de ocho dígitos")
  void whenSaleExceedsFiveDigitsThenKeepEightDigitLimit(String type) {
    // Separar los contadores no reduce la numeración permitida de los comprobantes.
    CounterEntityRepository counters = mock(CounterEntityRepository.class);
    TaxpayerEntityRepository taxpayers = mock(TaxpayerEntityRepository.class);
    UUID taxpayer = UUID.randomUUID();
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(taxpayer);
    when(counters.lockById(any())).thenReturn(Optional.of(CounterEntity.builder()
            .taxpayerId(taxpayer).nextNumber(100000).build()));
    assertThat(new SeriesStoreImpl(taxpayers, counters, CLOCK)
            .allocateNextNumber(ACCOUNT, COMPANY, type, "F001")).isEqualTo(100000);
  }
}
