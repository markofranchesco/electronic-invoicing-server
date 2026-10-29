package pe.invoicing.app.repository.artifact.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import pe.invoicing.app.repository.document.entity.DocumentEntity;
import pe.invoicing.app.repository.identity.entity.TaxpayerEntity;

import java.util.UUID;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"taxpayer_id", "key_hash"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IssueOperationEntity {
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

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
          name = "document_id",
          referencedColumnName = "id",
          insertable = false,
          updatable = false)
  private DocumentEntity document;

  @Column(name = "key_hash", nullable = false, length = 64)
  private String keyHash;

  @Column(name = "body_hash", nullable = false, length = 64)
  private String bodyHash;
}
