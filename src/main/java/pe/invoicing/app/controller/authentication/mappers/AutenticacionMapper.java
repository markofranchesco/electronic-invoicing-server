package pe.invoicing.app.controller.authentication.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import pe.invoicing.app.controller.authentication.dtos.requests.SignInRequest;
import pe.invoicing.app.controller.authentication.dtos.requests.SignUpRequest;
import pe.invoicing.app.service.authentication.models.Login;
import pe.invoicing.app.service.authentication.models.Registration;
import pe.invoicing.app.service.session.models.SessionClient;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AutenticacionMapper {
  @Mapping(target = "browser", ignore = true)
  @Mapping(target = "operatingSystem", ignore = true)
  @Mapping(target = "deviceType", ignore = true)
  SessionClient toClient(String ipAddress, String userAgent, String acceptLanguage);

  Registration toService(SignUpRequest request);

  Login toService(SignInRequest request);
}
