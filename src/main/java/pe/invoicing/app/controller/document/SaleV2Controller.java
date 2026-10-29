package pe.invoicing.app.controller.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.controller.document.dtos.requests.DocumentUpsertRequest;
import pe.invoicing.app.controller.document.mappers.DocumentMapper;
import pe.invoicing.app.controller.document.mappers.DocumentResponseMapper;
import pe.invoicing.app.service.document.impl.SaleV2Service;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v2/companies/{documentNumber}/sales")
public class SaleV2Controller {
  private final SaleV2Service sales;
  private final DocumentMapper mapper;
  private final DocumentResponseMapper responses;

  public record SaleRequest(@NotNull @Valid DocumentUpsertRequest document) {}
  public record SaleOperationResponse(UUID documentId, String message, String recoveryUrl,
          pe.invoicing.app.controller.document.dtos.responses.DocumentApiResponse document) {}

  @PostMapping
  public ResponseEntity<?> save(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber,
          @RequestHeader("Idempotency-Key") @NotBlank String key, @Valid @RequestBody SaleRequest request,
          @RequestParam(defaultValue = "TICKET_80") @Pattern(regexp = InputPatterns.PDF_FORMAT) String format) {
    var result = sales.save(documentNumber, key, mapper.toService(request.document()), true, format);
    return response(documentNumber, result);
  }

  @PostMapping("/{id}/recover")
  public ResponseEntity<?> send(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber,
          @PathVariable UUID id,
          @RequestParam(defaultValue = "TICKET_80") @Pattern(regexp = InputPatterns.PDF_FORMAT) String format) {
    return response(documentNumber, sales.send(documentNumber, id, format));
  }

  @GetMapping("/{id}")
  public Object status(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber, @PathVariable UUID id) {
    return responses.toApi(sales.status(documentNumber, id).document());
  }

  private ResponseEntity<?> response(String ruc, SaleV2Service.Result result) {
    var document = result.document();
    String location = "/v2/companies/" + ruc + "/sales/" + document.getId();
    if (result.pdf() != null)
      return ResponseEntity.ok().header("Content-Type", "application/pdf")
              .header("Content-Disposition", "inline; filename=\"comprobante.pdf\"")
              .header("X-Document-Id", document.getId().toString())
              .header("X-Emission-Status", document.getStatus()).header("Location", location).body(result.pdf());
    boolean failed = "REJECTED".equals(document.getStatus()) || "CANCELLED".equals(document.getStatus())
            || document.getReconciliation() != null && document.getReconciliation().isManualReview()
            || document.getCommunication() != null && "MANUAL_REVIEW".equals(document.getCommunication().getStatus());
    return ResponseEntity.status(failed ? 409 : 202)
            .header("Location", location).header("Retry-After", "2")
            .body(new SaleOperationResponse(document.getId(), failed
                    ? "La emisión requiere revisión. Consulta el motivo antes de reintentar."
                    : "La emisión está tardando más de lo esperado. La venta está registrada; recupera el resultado con el mismo identificador, sin crear otra venta.",
                    location + "/recover", responses.toApi(document)));
  }
}
