package pe.invoicing.app.controller.collection;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import pe.invoicing.app.service.collection.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/companies/{documentNumber}/documents/{documentId}/payments")
public class CollectionController {
  private final CollectionService collections;

  @GetMapping
  public CollectionBalance balance(@PathVariable String documentNumber, @PathVariable UUID documentId) {
    return collections.balance(documentNumber, documentId);
  }

  @PostMapping
  public CollectionEntry collect(@PathVariable String documentNumber, @PathVariable UUID documentId,
          @RequestHeader("Idempotency-Key") String key, @RequestBody CollectionRequest request) {
    return collections.collect(documentNumber, documentId, key, request);
  }

  public record ReversalRequest(String reason) { }

  @PostMapping("/{paymentId}/reverse")
  public CollectionEntry reverse(@PathVariable String documentNumber, @PathVariable UUID documentId,
          @PathVariable UUID paymentId, @RequestBody ReversalRequest request) {
    return collections.reverse(documentNumber, documentId, paymentId, request.reason());
  }
}
