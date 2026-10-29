package pe.invoicing.app.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import pe.invoicing.app.service.document.models.DocumentItem;
import pe.invoicing.app.service.document.models.LineAmounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineAmountsTest {
  @ParameterizedTest(name = "Afectación {0}, precio {1}, descuento {2}")
  @CsvSource({
          "10,118.00,0.00,100.00,18.00,118.00",
          "10,118.00,11.80,90.00,16.20,106.20",
          "20,118.00,0.00,118.00,0.00,118.00",
          "30,118.00,18.00,100.00,0.00,100.00",
          "10,0.03,0,0.03,0.00,0.03"
  })
  @DisplayName("Importes y redondeo")
  void whenLineHasValidAmountsThenCalculateExpectedTax(
          String code, String price, String discount, String base, String tax, String total) {
    // Los resultados esperados son valores independientes del cálculo implementado.
    LineAmounts result =
            LineAmounts.calculateLineAmounts(
                    DocumentItem.builder()
                            .quantity("1")
                            .unitPrice(price)
                            .discountAmount(discount)
                            .taxAffectationCode(code)
                            .build());
    assertThat(result.base()).isEqualByComparingTo(base);
    assertThat(result.tax()).isEqualByComparingTo(tax);
    assertThat(result.total()).isEqualByComparingTo(total);
  }

  @ParameterizedTest(name = "Cantidad {0}, precio {1}, descuento {2}")
  @CsvSource({
          "0,118,0",
          "-1,118,0",
          "1,0,0",
          "1,118,-1",
          "1,118,118",
          "1,118,119",
          "0.000001,0.000001,0"
  })
  @DisplayName("Importes inválidos")
  void whenLineCannotProducePositiveSaleThenReject(String quantity, String price, String discount) {
    // Se impide una línea negativa, gratuita accidental o descontada por completo.
    assertThatThrownBy(
            () ->
                    LineAmounts.calculateLineAmounts(
                            DocumentItem.builder()
                                    .quantity(quantity)
                                    .unitPrice(price)
                                    .discountAmount(discount)
                                    .taxAffectationCode("10")
                                    .build()))
            .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "Afectación no implementada {0}")
  @ValueSource(
          strings = {
                  "11", "12", "13", "14", "15", "16", "17", "21", "31", "32", "33", "34", "35", "36", "37",
                  "40", "99"
          })
  @DisplayName("No asumir impuesto cero")
  void whenAffectationUnsupportedThenRejectInsteadOfTreatingAsUnaffected(String code) {
    // Rechazar no equivale a implementar estos regímenes tributarios.
    assertThatThrownBy(
            () ->
                    LineAmounts.calculateLineAmounts(
                            DocumentItem.builder()
                                    .quantity("1")
                                    .unitPrice("118")
                                    .taxAffectationCode(code)
                                    .build()))
            .isInstanceOf(IllegalArgumentException.class);
  }
}
