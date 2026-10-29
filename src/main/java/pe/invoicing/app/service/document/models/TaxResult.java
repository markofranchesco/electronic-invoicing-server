package pe.invoicing.app.service.document.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

@AllArgsConstructor
@Builder(toBuilder = true)
@Getter
@Setter
@NoArgsConstructor
public class TaxResult {
  private String code;
  private String description;
  private List<String> observations;
  private OffsetDateTime checkedAt;
  private String ticket;
  private String source;
  private OffsetDateTime acceptedAt;
  private OffsetDateTime acceptanceDeadlineBase;
  private String acceptanceDateSource;
  private String reference;
}
