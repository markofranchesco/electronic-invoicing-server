package pe.invoicing.app.controller.creditnote.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.controller.creditnote.dtos.CreditNoteIssueRequest;
import pe.invoicing.app.service.creditnote.models.CreditNoteDraft;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CreditNoteMapper {
  CreditNoteDraft toService(CreditNoteIssueRequest request);
}
