package pe.invoicing.app.service.company.port;

import pe.invoicing.app.service.company.models.SequenceInfo;

import java.util.List;
import java.util.UUID;

public interface SeriesStore {

  List<SequenceInfo> listSequences(UUID accountId, String documentNumber);

  void ensureSequence(UUID accountId, String documentNumber, String type, String series);

  long allocateCommunicationNumber(UUID accountId, String documentNumber, String type, java.time.LocalDate date);

  long allocateNextNumber(UUID accountId, String documentNumber, String type, String series);
}
