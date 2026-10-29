package pe.invoicing.app.repository.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionEntity {
  @Id
  private UUID id;

  @Column(name = "hash", nullable = false, unique = true, length = 64)
  private String hash;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
          name = "account_id",
          referencedColumnName = "id",
          insertable = false,
          updatable = false)
  private AccountEntity account;

  @Column(name = "ip_address", length = 45)
  private String ipAddress;

  @Column(name = "user_agent", length = 1024)
  private String userAgent;

  @Column(name = "accept_language", length = 128)
  private String acceptLanguage;

  @Column(name = "browser", length = 100)
  private String browser;

  @Column(name = "operating_system", length = 100)
  private String operatingSystem;

  @Column(name = "device_type", length = 50)
  private String deviceType;

  @Column(nullable = false)
  private OffsetDateTime createdAt;

  private OffsetDateTime lastUsedAt;

  @Column(name = "revoked_at")
  private OffsetDateTime revokedAt;
}
