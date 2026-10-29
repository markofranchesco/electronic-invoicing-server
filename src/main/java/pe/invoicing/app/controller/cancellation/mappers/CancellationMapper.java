package pe.invoicing.app.controller.cancellation.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.controller.cancellation.dtos.CancellationApiResponse;
import pe.invoicing.app.controller.cancellation.dtos.CancellationRequest;
import pe.invoicing.app.service.cancellation.models.CancellationDetails;
import pe.invoicing.app.service.cancellation.models.CancellationState;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CancellationMapper {
  CancellationDetails toService(CancellationRequest request);

  CancellationApiResponse toApi(CancellationState state);
}
