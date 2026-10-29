package pe.invoicing.app.service.document.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.invoicing.app.service.authentication.AutenticacionService;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentFile;
import pe.invoicing.app.service.document.models.Publication;
import pe.invoicing.app.service.document.models.PublishedDocument;
import pe.invoicing.app.service.document.port.DocumentArtifactStore;
import pe.invoicing.app.service.document.port.DocumentStore;

import java.util.Set;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@RequiredArgsConstructor
public class PublicationService {
  private final DocumentStore store;
  private final DocumentArtifactStore artifacts;
  private final AutenticacionService auth;
  private final DocumentPdfRenderer pdfRenderer;

  public Publication publish(String documentNumber, UUID id) {
    DocumentData document = store.findDocument(auth.accountId(), documentNumber, id);
    if (document.getPublicationKey() == null) {
      if (artifacts.findArtifact(auth.accountId(), documentNumber, id, "XML").isEmpty())
        throw new ResponseStatusException(CONFLICT, "Falta el XML original.");
      document = store.publishDocument(auth.accountId(), documentNumber, id, UUID.randomUUID().toString().replace("-", ""));
    }
    return new Publication(document.getPublicationKey(), "/public/documents/" + document.getPublicationKey(),
            document.getIssueDate().plusYears(1));
  }

  public DocumentFile download(String key, String type) {
    if (key == null || !key.matches("[a-f0-9]{32}") || !Set.of("XML", "CDR", "PDF").contains(type))
      throw new ResponseStatusException(NOT_FOUND, "Publicación no encontrada.");
    PublishedDocument publication = store.findPublishedDocument(key)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Publicación no encontrada."));
    DocumentData document = publication.document();
    String filename = document.getDocumentNumber() + "-" + document.getDocumentType() + "-" + document.getSeries() + "-" + document.getNumber();
    if ("PDF".equals(type)) return new DocumentFile(filename + ".pdf", "application/pdf",
            pdfRenderer.render(document.getDocumentNumber(), document, "TICKET_80",
                    artifacts.findArtifact(publication.accountId(), document.getDocumentNumber(), document.getId(), "XML")));
    byte[] content = artifacts.findArtifact(publication.accountId(), document.getDocumentNumber(), document.getId(), type)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Archivo aún no disponible."));
    if ("CDR".equals(type) && document.getSeries().startsWith("B") && document.getTaxResponse() != null)
      filename = document.getDocumentNumber() + "-" + document.getTaxResponse().getReference();
    return new DocumentFile(("CDR".equals(type) ? "R-" : "") + filename + ("CDR".equals(type) ? ".zip" : ".xml"),
            "CDR".equals(type) ? "application/zip" : "application/xml", content);
  }

  public record PublicStatus(String documentNumber, String documentType, String series, Long number,
          java.time.LocalDate issueDate, String currency, String totalAmount, String emissionStatus,
          String sunatCode, Boolean xmlAvailable, Boolean cdrAvailable) { }

  public PublicStatus status(String key) {
    if (key == null || !key.matches("[a-f0-9]{32}"))
      throw new ResponseStatusException(NOT_FOUND, "Publicación no encontrada.");
    var d = store.findPublishedDocument(key)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Publicación no encontrada.")).document();
    return new PublicStatus(d.getDocumentNumber(), d.getDocumentType(), d.getSeries(), d.getNumber(),
            d.getIssueDate(), d.getCurrency(), d.getTotals().getTotalAmount(), d.getStatus(),
            d.getTaxResponse() == null ? null : d.getTaxResponse().getCode(), d.getXmlAvailable(), d.getCdrAvailable());
  }

  public String page(String key) {
    if (key == null || !key.matches("[a-f0-9]{32}"))
      throw new ResponseStatusException(NOT_FOUND, "Publicación no encontrada.");
    var document = store.findPublishedDocument(key)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Publicación no encontrada.")).document();
    String status = switch (document.getStatus()) {
      case "ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS" -> "Aceptado por SUNAT";
      case "REJECTED" -> "Rechazado por SUNAT";
      case "CANCELLED" -> "Comprobante anulado";
      case "SUBMITTED" -> "Enviado a SUNAT; respuesta en proceso";
      default -> "Emisión en revisión";
    };
    String prefix = "/public/documents/" + key;
    return "<!doctype html><html lang=es><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<meta name=robots content='noindex,nofollow'><title>Consulta de comprobante</title>"
            + "<style>body{font:16px system-ui;background:#f3f4f6;color:#17212c;margin:0;padding:32px 16px}main{max-width:560px;margin:auto;background:white;padding:28px;border-radius:12px}h1{font-size:24px}a{display:inline-block;padding:12px 16px;margin:4px;background:#17212c;color:white;border-radius:6px;text-decoration:none}small{color:#52606d}footer{margin-top:28px;border-top:1px solid #ddd;padding-top:16px}</style></head><body><main>"
            + "<small>CONSULTA DE COMPROBANTE</small><h1>" + html(document.getIssuerLegalName()) + "</h1>"
            + "<p>RUC " + html(document.getDocumentNumber()) + "</p><h2>" + html(document.getSeries()) + "-" + document.getNumber() + "</h2>"
            + "<p>Fecha: " + document.getIssueDate() + "</p><p>Total: " + html(document.getCurrency()) + " " + html(document.getTotals().getTotalAmount()) + "</p>"
            + "<p><strong>" + status + "</strong></p>"
            + "<a href='" + prefix + "/PDF'>Descargar PDF</a><a href='" + prefix + "/XML'>XML firmado</a>"
            + (Boolean.TRUE.equals(document.getCdrAvailable()) ? "<a href='" + prefix + "/CDR'>Constancia CDR</a>" : "<p>La constancia estará disponible cuando se procese la respuesta.</p>")
            + "<footer><small>Fábrica Abstracta S.A.C.S. · Facturación electrónica</small></footer></main></body></html>";
  }

  private static String html(String value) {
    return org.springframework.web.util.HtmlUtils.htmlEscape(java.util.Objects.toString(value, ""));
  }
}
