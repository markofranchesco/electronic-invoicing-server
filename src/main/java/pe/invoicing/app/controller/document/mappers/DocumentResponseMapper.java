package pe.invoicing.app.controller.document.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.controller.document.dtos.requests.DocumentSearchRequest;
import pe.invoicing.app.controller.document.dtos.responses.DocumentApiResponse;
import pe.invoicing.app.controller.document.dtos.responses.OriginalDocumentReference;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentReference;
import pe.invoicing.app.service.document.models.DocumentSearchCriteria;

import java.time.ZoneOffset;

@Mapper(
        componentModel = "spring",
        imports = ZoneOffset.class,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DocumentResponseMapper {
  @Mapping(target = "status", source = "apiStatus")
  @Mapping(
          target = "issueDate",
          expression = "java(document.getIssueDate().atStartOfDay().atOffset(ZoneOffset.ofHours(-5)))")
  @Mapping(target = "sunatCode", source = "taxResponse.code")
  @Mapping(target = "sunatDescription", source = "taxResponse.description")
  @Mapping(target = "referenceDocument", source = "note.originalDocument")
  @Mapping(target = "reasonCode", source = "note.reasonCode")
  @Mapping(target = "emissionStatus", source = "status")
  @Mapping(target = "communicationReference", source = "communication.batchReference")
  @Mapping(target = "communicationStatus", source = "communication.status")
  @Mapping(target = "cancellationStatus", source = "cancellation.status")
  @Mapping(target = "communicationTicket", source = "communication.ticket")
  @Mapping(target = "acceptedAt", source = "taxResponse.acceptedAt")
  @Mapping(target = "acceptanceDeadlineBase", source = "taxResponse.acceptanceDeadlineBase")
  @Mapping(target = "acceptanceDateSource", source = "taxResponse.acceptanceDateSource")
  DocumentApiResponse toApi(DocumentData document);

  OriginalDocumentReference toApi(DocumentReference reference);

  @Mapping(target = "documentNumber", source = "documentNumber")
  DocumentSearchCriteria toCriteria(DocumentSearchRequest request, String documentNumber);
}
