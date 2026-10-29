package pe.invoicing.app.service.document.models;

import lombok.Builder;

import java.util.List;

@Builder
public record SunatSendResult(
        String code, String description, List<String> observations, byte[] cdrZip) {
  public SunatSendResult {
    observations = List.copyOf(observations);
    cdrZip = cdrZip.clone();
  }

  @Override
  public byte[] cdrZip() {
    return cdrZip.clone();
  }

  public boolean accepted() {
    return "0".equals(code) || "0000".equals(code);
  }
}
