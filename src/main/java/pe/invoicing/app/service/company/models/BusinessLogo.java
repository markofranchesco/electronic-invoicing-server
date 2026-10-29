package pe.invoicing.app.service.company.models;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PNG/JPEG puro en Base64; no URL ni contenido ejecutable. */
public final class BusinessLogo {
  private BusinessLogo() {}

  public static byte[] validate(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      if (value.length() > 699052) throw new IllegalArgumentException();
      byte[] bytes = Base64.getDecoder().decode(value);
      if (bytes.length > 512 * 1024) throw new IllegalArgumentException();
      try (var stream = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) throw new IllegalArgumentException();
        var reader = readers.next();
        try {
          reader.setInput(stream);
          String format = reader.getFormatName();
          if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw new IllegalArgumentException();
          if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048) throw new IllegalArgumentException();
          if (reader.read(0) == null) throw new IllegalArgumentException();
        } finally { reader.dispose(); }
      }
      return bytes;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
              "Logo inválido: envía PNG/JPEG Base64 puro, máximo 512 KiB y 2048 x 2048 píxeles.", ex);
    }
  }
}
