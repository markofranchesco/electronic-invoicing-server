package pe.invoicing.app.service.document.models;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

@Builder(toBuilder = true)
@Value
@JsonDeserialize(builder = NoteDetails.NoteDetailsBuilder.class)
public class NoteDetails {
  private String reasonCode;
  private String reason;
  private DocumentReference originalDocument;

  @JsonPOJOBuilder(withPrefix = "")
  public static class NoteDetailsBuilder {
  }
}
