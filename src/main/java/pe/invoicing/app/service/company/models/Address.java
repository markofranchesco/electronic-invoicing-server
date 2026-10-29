package pe.invoicing.app.service.company.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Builder(toBuilder = true)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Address {
  private String street;
  private String geographicCode;
  private String department;
  private String province;
  private String district;
  private String countryCode;
}
