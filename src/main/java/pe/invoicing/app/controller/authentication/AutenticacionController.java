package pe.invoicing.app.controller.authentication;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.controller.authentication.dtos.requests.SignInRequest;
import pe.invoicing.app.controller.authentication.dtos.requests.SignUpRequest;
import pe.invoicing.app.controller.authentication.dtos.responses.TokenResponse;
import pe.invoicing.app.controller.authentication.mappers.AutenticacionMapper;
import pe.invoicing.app.controller.shared.dtos.responses.MessageResponse;
import pe.invoicing.app.service.authentication.AutenticacionService;

@Validated
@RestController
@RequiredArgsConstructor
public class AutenticacionController {
  private final AutenticacionMapper autenticacionMapper;
  private final AutenticacionService autenticacionService;

  @PostMapping("/sign-up")
  public TokenResponse signUp(@Valid @RequestBody SignUpRequest signUpRequest,
                              HttpServletRequest httpServletRequest,
                              @RequestHeader(value = "User-Agent", required = false) @Size(max = 1024, message = InputMessages.SIZE) String userAgent,
                              @RequestHeader(value = "Accept-Language", required = false) @Size(max = 128, message = InputMessages.SIZE) String acceptLanguage) {
    return TokenResponse.builder().accessToken(autenticacionService.signUp(autenticacionMapper.toService(signUpRequest), autenticacionMapper.toClient(httpServletRequest.getRemoteAddr(), userAgent, acceptLanguage))).message("Cuenta creada correctamente.").build();
  }

  @PostMapping("/sign-in")
  public TokenResponse signIn(@Valid @RequestBody SignInRequest signInRequest,
                              HttpServletRequest httpServletRequest,
                              @RequestHeader(value = "User-Agent", required = false) @Size(max = 1024, message = InputMessages.SIZE) String userAgent,
                              @RequestHeader(value = "Accept-Language", required = false) @Size(max = 128, message = InputMessages.SIZE) String acceptLanguage) {
    return TokenResponse.builder().accessToken(autenticacionService.signIn(autenticacionMapper.toService(signInRequest), autenticacionMapper.toClient(httpServletRequest.getRemoteAddr(), userAgent, acceptLanguage))).message("Sesión iniciada correctamente.").build();
  }

  @PostMapping("/sign-out")
  public MessageResponse signOut() {
    autenticacionService.signOut();
    return MessageResponse.builder().message("Sesión cerrada correctamente.").build();
  }
}
