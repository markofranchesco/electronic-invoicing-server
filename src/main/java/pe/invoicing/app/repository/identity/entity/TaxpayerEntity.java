package pe.invoicing.app.repository.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
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

import java.time.OffsetDateTime;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "document_number"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxpayerEntity {
  @Id
  private UUID id;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
          name = "account_id",
          referencedColumnName = "id",
          insertable = false,
          updatable = false)
  private AccountEntity account;

  @Column(name = "document_number", nullable = false, length = 11)
  private String documentNumber;

  @Enumerated(STRING)
  @Column(nullable = false)
  private TaxpayerType taxpayerType;

  @Column(nullable = false, length = 250)
  private String legalName;

  private String tradeName;

  @Column(columnDefinition = "text")
  private String logoBase64;

  // Periodo de elegibilidad verificado por el contribuyente para la Ley 31556.
  private java.time.LocalDate specialIgvFrom;
  private java.time.LocalDate specialIgvUntil;

  @Column(nullable = false)
  private String email;

  @Embedded
  private Address address;

  @Column(columnDefinition = "text", nullable = false)
  private String encryptedCredentials;

  @Column(columnDefinition = "text", nullable = false)
  private String encryptedCertificate;

  private OffsetDateTime createdAt;

  private OffsetDateTime updatedAt;

  public enum TaxpayerType {
    NATURAL,
    LEGAL
  }

  @Getter
  @Setter
  @Embeddable
  @NoArgsConstructor
  public static class Address {
    private String street;

    private String department;

    private String province;

    private String district;

    private String geographicCode;

    private String countryCode;
  }
}
