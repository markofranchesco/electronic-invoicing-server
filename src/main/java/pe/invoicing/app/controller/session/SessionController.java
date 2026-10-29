package pe.invoicing.app.controller.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.controller.session.dtos.responses.SessionApiResponse;
import pe.invoicing.app.controller.session.mappers.SessionResponseMapper;
import pe.invoicing.app.controller.shared.dtos.responses.MessageResponse;
import pe.invoicing.app.controller.shared.dtos.responses.PageResponse;
import pe.invoicing.app.service.session.SessionService;

import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
public class SessionController {
  private final SessionService sessions;
  private final SessionResponseMapper mapper;

  @GetMapping("/sessions")
  public PageResponse<SessionApiResponse> listSessions(@RequestParam(defaultValue = "1") @Min(value = 1, message = InputMessages.MINIMUM) int page,
                                                       @RequestParam(defaultValue = "10") @Min(value = 1, message = InputMessages.MINIMUM) @Max(value = 100, message = InputMessages.MAXIMUM) int perPage) {
    return PageResponse.from(sessions.listSessions(PageRequest.of(page - 1, perPage, Sort.by(Sort.Direction.DESC, "createdAt"))).map(mapper::toApi));
  }

  @DeleteMapping("/sessions/{sessionId}")
  public MessageResponse revokeSession(@PathVariable UUID sessionId) {
    sessions.revokeSession(sessionId);
    return MessageResponse.builder().message("Sesión revocada correctamente.").build();
  }
}
