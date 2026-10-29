package pe.invoicing.app.config.security.encryption;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import pe.invoicing.app.service.authentication.port.SecretVault;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class Vault implements SecretVault {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final SecretKey key;

  public Vault(Environment environment) {
    String secret = environment.getRequiredProperty("app.security.secret");
    try {
      key =
              new SecretKeySpec(
                      MessageDigest.getInstance("SHA-256")
                              .digest(("pe.invoicing.app.vault.v1:" + secret).getBytes(StandardCharsets.UTF_8)),
                      "AES");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("No se pudo preparar la clave de cifrado.", exception);
    }
  }

  @Override
  public String encrypt(String raw) {
    try {
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      return Base64.getEncoder().encodeToString(iv)
              + ":"
              + Base64.getEncoder()
              .encodeToString(cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException("No fue posible proteger esta información.", exception);
    }
  }

  @Override
  public String decrypt(String encoded) {
    try {
      String[] parts = encoded.split(":", 2);
      byte[] iv = Base64.getDecoder().decode(parts[0]);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
      return new String(
              cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException(
              "No fue posible recuperar la información protegida.", exception);
    }
  }
}
