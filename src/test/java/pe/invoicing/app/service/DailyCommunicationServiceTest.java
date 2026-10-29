package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.repository.document.CounterEntityRepository;
import pe.invoicing.app.repository.document.entity.CounterEntity;
import pe.invoicing.app.repository.document.impl.SeriesStoreImpl;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DailyCommunicationServiceTest extends AbstractServiceTest {
  @ParameterizedTest(name = "Comunicación {0}")
  @ValueSource(strings = {"RA", "RC"})
  @DisplayName("Numeración diaria independiente")
  void whenDateChangesThenStartNewCommunicationSequence(String type) {
    // Agotar ayer no bloquea hoy y ambos tipos mantienen correlativos independientes.
    var repository = mock(CounterEntityRepository.class);
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var counters = new HashMap<UUID, CounterEntity>();
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    when(repository.lockById(any())).thenAnswer(c -> Optional.ofNullable(counters.get(c.getArgument(0))));
    when(repository.saveAndFlush(any())).thenAnswer(c -> {
      CounterEntity e = c.getArgument(0);
      counters.put(e.getId(), e);
      return e;
    });
    var adapter = new SeriesStoreImpl(taxpayers, repository, CLOCK);
    var date = LocalDate.of(2026, 10, 26);
    assertThat(adapter.allocateCommunicationNumber(ACCOUNT, COMPANY, type, date)).isEqualTo(1);
    assertThat(adapter.allocateCommunicationNumber(ACCOUNT, COMPANY, type, date)).isEqualTo(2);
    assertThat(adapter.allocateCommunicationNumber(ACCOUNT, COMPANY, type, date.plusDays(1))).isEqualTo(1);
    assertThat(adapter.allocateCommunicationNumber(ACCOUNT, COMPANY, "RA".equals(type) ? "RC" : "RA", date)).isEqualTo(1);
  }

  @ParameterizedTest(name = "Migración {0}")
  @ValueSource(strings = {"RA", "RC"})
  @DisplayName("No repetir referencias existentes")
  void whenLegacyCounterExistsThenContinueTodayAndResetTomorrow(String type) {
    // Al activar contadores diarios no se vuelve a usar una referencia ya emitida hoy.
    var repository = mock(CounterEntityRepository.class);
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var counters = new HashMap<UUID, CounterEntity>();
    UUID legacyId = UUID.nameUUIDFromBytes((ACCOUNT + ":" + COMPANY + ":" + type + ":" + type + "01")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    counters.put(legacyId, CounterEntity.builder().id(legacyId).taxpayerId(ACCOUNT).series(type + "01").nextNumber(500).build());
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    when(repository.lockById(any())).thenAnswer(c -> Optional.ofNullable(counters.get(c.getArgument(0))));
    when(repository.saveAndFlush(any())).thenAnswer(c -> {
      CounterEntity e = c.getArgument(0);
      counters.put(e.getId(), e);
      return e;
    });
    var adapter = new SeriesStoreImpl(taxpayers, repository, CLOCK);
    var date = LocalDate.of(2026, 10, 26);
    assertThat(adapter.allocateCommunicationNumber(ACCOUNT, COMPANY, type, date)).isEqualTo(500);
    assertThat(adapter.allocateCommunicationNumber(ACCOUNT, COMPANY, type, date.plusDays(1))).isEqualTo(1);
  }

  @org.junit.jupiter.api.Test
  @DisplayName("Migrar contador antiguo agotado")
  void whenLegacyCounterExhaustedThenMigrationAllowsTomorrow() {
    // El corte se confirma antes de emitir, incluso si la numeración antigua ya se agotó.
    var repository = mock(CounterEntityRepository.class);
    var taxpayers = mock(TaxpayerEntityRepository.class);
    var counters = new HashMap<UUID, CounterEntity>();
    var taxpayer = pe.invoicing.app.repository.identity.entity.TaxpayerEntity.builder()
            .id(ACCOUNT).accountId(ACCOUNT).documentNumber(COMPANY).build();
    UUID id = UUID.nameUUIDFromBytes((ACCOUNT + ":" + COMPANY + ":RC:RC01").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var legacy = CounterEntity.builder().id(id).taxpayerId(ACCOUNT).taxpayer(taxpayer)
            .documentType(CounterEntity.DocumentType.DESPATCH_ADVICE).series("RC01").nextNumber(100000).build();
    counters.put(id, legacy);
    when(repository.findAll()).thenReturn(java.util.List.of(legacy));
    when(taxpayers.companyId(ACCOUNT, COMPANY)).thenReturn(ACCOUNT);
    when(taxpayers.lockCompany(ACCOUNT, COMPANY)).thenReturn(Optional.of(taxpayer));
    when(repository.lockById(any())).thenAnswer(c -> Optional.ofNullable(counters.get(c.getArgument(0))));
    when(repository.saveAndFlush(any())).thenAnswer(c -> {
      CounterEntity e = c.getArgument(0);
      counters.put(e.getId(), e);
      return e;
    });
    var adapter = new SeriesStoreImpl(taxpayers, repository, CLOCK);
    adapter.migrateCommunicationCounters();
    assertThat(adapter.allocateCommunicationNumber(ACCOUNT, COMPANY, "RC", legacy.getCommunicationDate().plusDays(1))).isEqualTo(1);
  }
}
