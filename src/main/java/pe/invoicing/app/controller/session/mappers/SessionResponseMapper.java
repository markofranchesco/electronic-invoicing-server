package pe.invoicing.app.controller.session.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.controller.session.dtos.responses.SessionApiResponse;
import pe.invoicing.app.service.session.models.SessionDetails;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SessionResponseMapper {
  SessionApiResponse toApi(SessionDetails details);
}
