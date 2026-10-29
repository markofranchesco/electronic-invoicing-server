package pe.invoicing.app.repository.document.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.repository.document.CounterEntityRepository;
import pe.invoicing.app.repository.document.entity.CounterEntity;
import pe.invoicing.app.repository.identity.TaxpayerEntityRepository;
import pe.invoicing.app.service.company.models.SequenceInfo;
import pe.invoicing.app.service.company.port.SeriesStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SeriesStoreImpl implements SeriesStore {
  private final TaxpayerEntityRepository taxpayers;
  private final CounterEntityRepository counters;
  private final java.time.Clock clock;

  private UUID key(UUID accountId, String documentNumber, String documentType, String series) {
    return UUID.nameUUIDFromBytes(
            (accountId + ":" + documentNumber + ":" + documentType + ":" + series)
                    .getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public List<SequenceInfo> listSequences(UUID accountId, String documentNumber) {
    return counters.findByTaxpayerId(taxpayers.companyId(accountId, documentNumber)).stream()
            .filter(counter -> counter.getCommunicationDate() == null)
            .map(
                    counter ->
                            new SequenceInfo(
                                    switch (counter.getDocumentType()) {
                                      case INVOICE -> "01";
                                      case BOLETA -> "03";
                                      case CREDIT_NOTE -> "07";
                                      case DEBIT_NOTE -> "08";
                                      case DESPATCH_ADVICE -> counter.getSeries().startsWith("RC") ? "RC" : "RA";
                                    },
                                    counter.getSeries(),
                                    counter.getNextNumber()))
            .toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void ensureSequence(
          UUID accountId, String documentNumber, String documentType, String series) {
    UUID id = key(accountId, documentNumber, documentType, series);
    if (counters.existsById(id)) {
      return;
    }
    counters.save(
            CounterEntity.builder()
                    .id(id)
                    .taxpayerId(taxpayers.companyId(accountId, documentNumber))
                    .documentType(
                            switch (documentType) {
                              case "01" -> CounterEntity.DocumentType.INVOICE;
                              case "03" -> CounterEntity.DocumentType.BOLETA;
                              case "07" -> CounterEntity.DocumentType.CREDIT_NOTE;
                              case "08" -> CounterEntity.DocumentType.DEBIT_NOTE;
                              case "RA", "RC" -> CounterEntity.DocumentType.DESPATCH_ADVICE;
                              default -> throw new ResponseStatusException(
                                      HttpStatus.UNPROCESSABLE_CONTENT, "Tipo de documento no soportado.");
                            })
                    .series(series)
                    .nextNumber(1)
                    .build());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public long allocateNextNumber(
          UUID accountId, String documentNumber, String documentType, String series) {
    CounterEntity counter =
            counters
                    .lockById(key(accountId, documentNumber, documentType, series))
                    .filter(
                            value ->
                                    taxpayers.companyId(accountId, documentNumber).equals(value.getTaxpayerId()))
                    .orElseThrow(
                            () ->
                                    new ResponseStatusException(
                                            HttpStatus.UNPROCESSABLE_CONTENT, "Serie no configurada."));
    long nextNumber = counter.getNextNumber();
    if (nextNumber < 1 || nextNumber > (java.util.Set.of("RA", "RC").contains(documentType) ? 99999 : 99999999)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Numeración agotada.");
    }
    counter.setNextNumber(nextNumber + 1);
    counters.saveAndFlush(counter);
    return nextNumber;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public long allocateCommunicationNumber(UUID accountId, String documentNumber, String type, java.time.LocalDate date) {
    if (!java.util.Set.of("RA", "RC").contains(type) || date == null)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Comunicación inválida.");
    // El llamador mantiene el bloqueo de empresa durante asignación y persistencia del documento.
    UUID id = key(accountId, documentNumber, type, date.toString());
    CounterEntity counter = counters.lockById(id).orElseGet(() -> {
      long first = 1;
      var legacy = counters.lockById(key(accountId, documentNumber, type, type + "01"));
      if (legacy.isPresent() && legacy.orElseThrow().getCommunicationDate() == null) {
        first = legacy.orElseThrow().getNextNumber();
        legacy.orElseThrow().setCommunicationDate(date);
        counters.saveAndFlush(legacy.orElseThrow());
      }
      return CounterEntity.builder().id(id).taxpayerId(taxpayers.companyId(accountId, documentNumber))
              .documentType(CounterEntity.DocumentType.DESPATCH_ADVICE).series(type + "01")
              .communicationDate(date).nextNumber(first).build();
    });
    long number = counter.getNextNumber();
    if (number < 1 || number > 99999)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Numeración diaria agotada.");
    counter.setNextNumber(number + 1);
    counters.saveAndFlush(counter);
    return number;
  }

  @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  @Transactional(rollbackFor = Exception.class)
  public void migrateCommunicationCounters() {
    var date = java.time.LocalDate.now(clock);
    for (CounterEntity legacy : counters.findAll()) {
      if (legacy.getCommunicationDate() != null || legacy.getDocumentType() != CounterEntity.DocumentType.DESPATCH_ADVICE
              || !java.util.Set.of("RC01", "RA01").contains(legacy.getSeries())) continue;
      var taxpayer = legacy.getTaxpayer();
      taxpayers.lockCompany(taxpayer.getAccountId(), taxpayer.getDocumentNumber()).orElseThrow();
      String type = legacy.getSeries().substring(0, 2);
      UUID dailyId = key(taxpayer.getAccountId(), taxpayer.getDocumentNumber(), type, date.toString());
      if (counters.lockById(dailyId).isEmpty()) counters.saveAndFlush(CounterEntity.builder().id(dailyId)
              .taxpayerId(taxpayer.getId()).documentType(legacy.getDocumentType()).series(legacy.getSeries())
              .communicationDate(date).nextNumber(legacy.getNextNumber()).build());
      legacy.setCommunicationDate(date);
      counters.saveAndFlush(legacy);
    }
  }

}
