package pe.invoicing.app.controller.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;
import pe.invoicing.app.controller.document.dtos.requests.DocumentSearchRequest;
import pe.invoicing.app.controller.document.dtos.requests.DocumentUpsertRequest;
import pe.invoicing.app.controller.document.dtos.requests.RecoveryRequest;
import pe.invoicing.app.controller.document.dtos.requests.SummaryReportRequest;
import pe.invoicing.app.controller.document.dtos.responses.DocumentApiResponse;
import pe.invoicing.app.controller.document.mappers.DocumentMapper;
import pe.invoicing.app.controller.document.mappers.DocumentResponseMapper;
import pe.invoicing.app.controller.shared.dtos.responses.AsyncOperationResponse;
import pe.invoicing.app.controller.shared.dtos.responses.PageResponse;
import pe.invoicing.app.service.document.DocumentService;

import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/companies/{documentNumber}/documents")
public class DocumentController {
  private final DocumentMapper mapper;
  private final DocumentResponseMapper responseMapper;
  private final DocumentService documents;

  @GetMapping
  public PageResponse<DocumentApiResponse> list(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber,
                                                @Valid @ModelAttribute DocumentSearchRequest filters) {
    return PageResponse.from(documents.listDocuments(responseMapper.toCriteria(filters, documentNumber), PageRequest.of(filters.getPage() - 1, filters.getPerPage(), Sort.by(Sort.Direction.DESC, "createdAt"))).map(responseMapper::toApi));
  }

  @PostMapping("/issue")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public AsyncOperationResponse issue(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber,
                                      @RequestHeader("Idempotency-Key") @NotBlank(message = InputMessages.REQUIRED) String key,
                                      @Valid @RequestBody DocumentUpsertRequest request) {
    return AsyncOperationResponse.builder().message("Solicitud de emisión recibida.").documentId(documents.acceptNewDocument(documentNumber, key, mapper.toService(request)).getDocumentId()).status("PENDING").build();
  }

  @GetMapping("/{documentId}")
  public DocumentApiResponse get(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber,
                                 @PathVariable UUID documentId) {
    return responseMapper.toApi(documents.getDocument(documentNumber, documentId));
  }

  @GetMapping(value = "/{documentId}/receipt", produces = "application/pdf")
  public ResponseEntity<byte[]> downloadPdf(@PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER, message = InputMessages.TAX_NUMBER) String documentNumber,
                                            @PathVariable UUID documentId, @RequestParam(defaultValue = "TICKET_55") @Pattern(regexp = InputPatterns.PDF_FORMAT, message = InputMessages.FORMAT) String format) {
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"documento.pdf\"").body(documents.downloadPdf(documentNumber, documentId, format));
  }

  @GetMapping("/{documentId}/files/{type}")
  public ResponseEntity<byte[]> downloadFile(
          @PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber,
          @PathVariable UUID documentId,
          @PathVariable @Pattern(regexp = "XML|CDR|SUMMARY_XML|SUMMARY_CDR|CANCELLATION_XML|CANCELLATION_CDR") String type) {
    var file = documents.downloadFile(documentNumber, documentId, type);
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, file.contentType())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
            .body(file.content());
  }

  @PostMapping("/{documentId}/recover")
  public DocumentApiResponse recover(
          @PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber,
          @PathVariable UUID documentId,
          @Valid @RequestBody RecoveryRequest request) {
    return responseMapper.toApi(documents.recoverDocument(documentNumber, documentId, request.ticket()));
  }

  @PostMapping("/{documentId}/summary")
  public DocumentApiResponse reportSummary(
          @PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber,
          @PathVariable UUID documentId,
          @Valid @RequestBody SummaryReportRequest request) {
    return responseMapper.toApi(documents.reportSummary(documentNumber, documentId, request.previousReference()));
  }

  @PostMapping("/{documentId}/grant")
  public DocumentApiResponse grant(
          @PathVariable @Pattern(regexp = InputPatterns.TAX_NUMBER) String documentNumber,
          @PathVariable UUID documentId) {
    return responseMapper.toApi(documents.markGranted(documentNumber, documentId));
  }

  @GetMapping("/{documentId}/communications/{reference}/files/{type}")
  public ResponseEntity<byte[]> history(@PathVariable String documentNumber, @PathVariable UUID documentId,
                                        @PathVariable String reference, @PathVariable String type) {
    var file = documents.downloadCommunicationFile(documentNumber, documentId, reference, type);
    return ResponseEntity.ok().header("Content-Type", file.contentType())
            .header("Content-Disposition", "attachment; filename=\"" + file.filename() + "\"").body(file.content());
  }

  @PostMapping("/{documentId}/reconciliation/resume")
  public void resume(@PathVariable String documentNumber, @PathVariable UUID documentId) {
    documents.resumeReconciliation(documentNumber, documentId);
  }

  @GetMapping("/{documentId}/communications")
  public java.util.List<pe.invoicing.app.service.document.models.CommunicationRecord> history(
          @PathVariable String documentNumber, @PathVariable UUID documentId) {
    return documents.communicationHistory(documentNumber, documentId);
  }

}
