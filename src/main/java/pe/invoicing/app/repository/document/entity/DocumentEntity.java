package pe.invoicing.app.repository.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;

import java.time.OffsetDateTime;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(
        uniqueConstraints =
        @UniqueConstraint(columnNames = {"taxpayer_id", "document_type", "series", "number"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentEntity {
  @Id
  private UUID id;

  @Column(name = "source_document_id")
  private UUID sourceDocumentId;

  @Column(unique = true, length = 64)
  private String publicationKey;

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
  private CounterEntity.DocumentType documentType;

  @Column(nullable = false, length = 4)
  private String series;

  @Column(nullable = false)
  private long number;

  @Enumerated(STRING)
  @Column(nullable = false)
  private DocumentStatus status;

  @Column(name = "external_reference")
  private String externalReference;

  @Column(name = "issue_date", nullable = false)
  private OffsetDateTime issueDate;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public enum DocumentStatus {
    CREATED,
    SIGNED,
    ACCEPTED,
    REJECTED,
    ANNULLED
  }
}
