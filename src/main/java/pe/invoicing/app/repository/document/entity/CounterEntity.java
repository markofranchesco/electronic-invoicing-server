package pe.invoicing.app.repository.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;

import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CounterEntity {
  @Id
  private UUID id;

  @Column(name = "taxpayer_id", nullable = false)
  private UUID taxpayerId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
          name = "taxpayer_id",
          referencedColumnName = "id",
          insertable = false,
          updatable = false)
  private TaxpayerEntity taxpayer;

  @Enumerated(STRING)
  @Column(name = "document_type", nullable = false)
  private DocumentType documentType;

  @Column(nullable = false, length = 4)
  private String series;

  @Column(nullable = false)
  private long nextNumber;

  private java.time.LocalDate communicationDate;

  public enum DocumentType {
    INVOICE,
    BOLETA,
    CREDIT_NOTE,
    DEBIT_NOTE,
    DESPATCH_ADVICE
  }
}
