package pe.invoicing.app.controller.authentication.dtos.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;
import pe.invoicing.app.config.validation.constants.InputMessages;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = SignUpRequest.SignUpRequestBuilder.class)
public class SignUpRequest {
  @NotBlank(message = InputMessages.EMAIL_REQUIRED)
  @Email(message = InputMessages.EMAIL)
  @Size(max = 254, message = InputMessages.EMAIL_LENGTH)
  private String email;

  @NotBlank(message = InputMessages.PASSWORD_REQUIRED)
  @Size(min = 12, max = 128, message = InputMessages.PASSWORD_LENGTH)
  private String password;

  @JsonPOJOBuilder(withPrefix = "")
  public static class SignUpRequestBuilder {
  }
}
