package pe.invoicing.app.controller.document.dtos.requests;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import pe.invoicing.app.config.validation.constants.InputMessages;
import pe.invoicing.app.config.validation.constants.InputPatterns;

import java.time.LocalDate;

@Getter
@Setter
public class DocumentSearchRequest {
  @Min(value = 1, message = InputMessages.MINIMUM)
  private int page = 1;

  @Min(value = 1, message = InputMessages.MINIMUM)
  @Max(value = 100, message = InputMessages.MAXIMUM)
  private int perPage = 10;

  @Pattern(regexp = InputPatterns.DOCUMENT_TYPES, message = InputMessages.DOCUMENT_TYPE)
  private String documentType;

  @Pattern(regexp = InputPatterns.DOCUMENT_STATUSES, message = InputMessages.FORMAT)
  private String status;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate from;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private LocalDate to;

  @Pattern(regexp = InputPatterns.SERIES, message = InputMessages.SERIES)
  private String series;

  @Min(value = 1, message = InputMessages.MINIMUM)
  private Long number;

  @Size(max = 255, message = InputMessages.SIZE)
  private String externalReference;
}
