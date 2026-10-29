package pe.invoicing.app.controller.document;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.invoicing.app.service.document.impl.PublicationService;
import pe.invoicing.app.service.document.models.Publication;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PublicationController {
  private final PublicationService publications;

  @PostMapping("/companies/{documentNumber}/documents/{documentId}/publication")
  public Publication publish(@PathVariable String documentNumber, @PathVariable UUID documentId) {
    return publications.publish(documentNumber, documentId);
  }

  @GetMapping(value = "/public/documents/{key}", produces = "text/html;charset=UTF-8")
  public String page(@PathVariable String key) {
    return publications.page(key);
  }

  @GetMapping("/public/documents/{key}/status")
  public ResponseEntity<PublicationService.PublicStatus> status(@PathVariable String key) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(publications.status(key));
  }

  @GetMapping("/public/documents/{key}/{type}")
  public ResponseEntity<byte[]> file(@PathVariable String key, @PathVariable String type) {
    var file = publications.download(key, type);
    return ResponseEntity.ok().header("Content-Type", file.contentType())
            .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"").body(file.content());
  }
}
