package pe.invoicing.app.repository.artifact.entity;

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
import pe.invoicing.app.repository.document.entity.DocumentEntity;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;

import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentArtifactEntity {
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

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
          name = "document_id",
          referencedColumnName = "id",
          insertable = false,
          updatable = false)
  private DocumentEntity document;

  @Enumerated(STRING)
  @Column(name = "artifact_type", nullable = false, length = 32)
  private ArtifactType artifactType;

  @Column(nullable = false, length = 10000000)
  private byte[] content;

  public enum ArtifactType {
    SIGNED_XML,
    CDR_ZIP,
    SUMMARY_XML,
    CANCELLATION_XML,
    CANCELLATION_CDR
  }
}
