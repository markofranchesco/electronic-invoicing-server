package pe.invoicing.app.config.security.crypto;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;
import pe.invoicing.app.service.authentication.port.SecurityCryptography;
import pe.invoicing.app.service.session.models.AuthPrincipal;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Component
public final class Crypto implements SecurityCryptography {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final Clock clock;
  private final NimbusJwtEncoder encoder;
  private final NimbusJwtDecoder decoder;

  public Crypto(@Value("${app.security.secret}") String secret, Clock clock) {
    this.clock = clock;
    encoder = new NimbusJwtEncoder(new ImmutableSecret<>(HexFormat.of().parseHex(sha256(secret))));
    decoder =
            NimbusJwtDecoder.withSecretKey(
                            new SecretKeySpec(HexFormat.of().parseHex(sha256(secret)), "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator();
    timestamps.setClock(clock);
    decoder.setJwtValidator(
            new DelegatingOAuth2TokenValidator<>(
                    timestamps, new JwtIssuerValidator("electronic-invoicing")));
  }

  private static String pbkdf(String s, byte[] salt) {
    try {
      PBEKeySpec spec = new PBEKeySpec(s.toCharArray(), salt, 210000, 256);
      try {
        return Base64.getEncoder()
                .encodeToString(
                        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                .generateSecret(spec)
                                .getEncoded());
      } finally {
        spec.clearPassword();
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("No se pudo derivar la clave de contraseña.", e);
    }
  }

  @Override
  public String generateToken(UUID accountId, UUID sessionId) {
    return encoder
            .encode(
                    JwtEncoderParameters.from(
                            JwsHeader.with(MacAlgorithm.HS256).build(),
                            JwtClaimsSet.builder()
                                    .issuer("electronic-invoicing")
                                    .subject(accountId.toString())
                                    .id(sessionId.toString())
                                    .issuedAt(clock.instant())
                                    .expiresAt(clock.instant().plusSeconds(43200))
                                    .build()))
            .getTokenValue();
  }

  @Override
  public AuthPrincipal verifyToken(String token) {
    Jwt jwt = decoder.decode(token);
    return new AuthPrincipal(UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject()));
  }

  @Override
  public String sha256(String input) {
    return sha256(input.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String sha256(byte[] input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String hashPassword(String password) {
    if (Optional.ofNullable(password).filter(value -> value.length() >= 12).isEmpty())
      throw new IllegalArgumentException("La contraseña debe tener al menos 12 caracteres");
    byte[] salt = new byte[16];
    RANDOM.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt) + ":" + pbkdf(password, salt);
  }

  @Override
  public boolean checkPassword(String candidate, String stored) {
    if (Optional.ofNullable(candidate).isEmpty() || Optional.ofNullable(stored).isEmpty())
      return false;
    String[] parts = stored.split(":", 2);
    if (parts.length != 2) return false;
    try {
      byte[] salt = Base64.getDecoder().decode(parts[0]);
      byte[] expected = Base64.getDecoder().decode(parts[1]);
      byte[] actual = Base64.getDecoder().decode(pbkdf(candidate, salt));
      return MessageDigest.isEqual(expected, actual);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
