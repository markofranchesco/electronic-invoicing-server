package pe.invoicing.app.service.document.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pe.invoicing.app.service.cancellation.models.CancellationState;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Builder(toBuilder = true)
@Getter
@Setter
@NoArgsConstructor
public class DocumentData {
  private String issuerLegalName;
  private String issuerLogoBase64;
  private String issuerFiscalAddress;
  private pe.invoicing.app.service.company.models.Address issuerAddress;
  private Establishment establishment;
  private UUID id;
  private Long version;
  private UUID sourceDocumentId;
  @JsonIgnore
  private UUID taxpayerId;
  private String documentNumber;
  private String environment;
  private String documentType;
  private pe.invoicing.app.service.document.models.Detraction detraction;
  private String series;
  private Long number;
  private LocalDate issueDate;
  private String currency;
  private Customer customer;
  private List<DocumentItem> items;
  private Payment payment;
  private NoteDetails note;
  private DocumentTotals totals;
  private String status;
  private TaxResult taxResponse;
  private CancellationState cancellation;
  private java.util.List<CancellationState> cancellationHistory;
  private Boolean xmlAvailable;
  private Boolean cdrAvailable;
  private String externalReference;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private CommunicationState communication;
  private java.util.List<CommunicationState> communicationHistory;
  private String exchangeRate;
  private String operationType;
  private String observations;
  private String signedXmlSha256;
  private OffsetDateTime issuedAt;
  private OffsetDateTime reportedAt;
  private OffsetDateTime grantedAt;
  private String publicationKey;
  private ReconciliationState reconciliation;
  private java.util.List<pe.invoicing.app.service.collection.CollectionEntry> collections;

  @JsonIgnore
  public String getApiStatus() {
    return switch (status) {
      case "ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS" -> "ACCEPTED";
      case "REJECTED" -> "REJECTED";
      case "CANCELLED" -> "ANNULLED";
      case "SIGNED", "UNKNOWN", "SUBMITTED" -> "SIGNED";
      default -> "CREATED";
    };
  }
}
