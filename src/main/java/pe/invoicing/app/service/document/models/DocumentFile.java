package pe.invoicing.app.service.document.models;

public record DocumentFile(String filename, String contentType, byte[] content) {
  public DocumentFile {
    content = content.clone();
  }

  @Override
  public byte[] content() {
    return content.clone();
  }
}
