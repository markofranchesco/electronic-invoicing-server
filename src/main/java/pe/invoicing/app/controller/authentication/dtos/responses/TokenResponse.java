package pe.invoicing.app.controller.authentication.dtos.responses;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Value
@Builder
@JsonDeserialize(builder = TokenResponse.TokenResponseBuilder.class)
public class TokenResponse {
  private String accessToken;
  private String message;

  @JsonPOJOBuilder(withPrefix = "")
  public static class TokenResponseBuilder {
  }
}
