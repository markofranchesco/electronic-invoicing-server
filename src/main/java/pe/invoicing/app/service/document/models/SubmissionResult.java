package pe.invoicing.app.service.document.models;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class SubmissionResult {
  UUID documentId;
}
