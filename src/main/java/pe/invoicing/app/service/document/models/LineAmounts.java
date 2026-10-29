package pe.invoicing.app.service.document.models;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.Set;

@Builder
public record LineAmounts(
        String affectation,
        BigDecimal base,
        BigDecimal tax,
        BigDecimal total,
        BigDecimal unitValue,
        BigDecimal baseBeforeDiscount,
        BigDecimal discountBase,
        BigDecimal discountTotal,
        BigDecimal icbper) {
  public static BigDecimal taxRate(DocumentItem item) {
    if (!"10".equals(item.getTaxAffectationCode())) return BigDecimal.ZERO;
    BigDecimal rate = new BigDecimal(Optional.ofNullable(item.getIgvRate()).orElse("18.00"));
    if (!Set.of(new BigDecimal("18"), new BigDecimal("10.5"), new BigDecimal("10"), new BigDecimal("15"))
            .stream().anyMatch(allowed -> allowed.compareTo(rate) == 0))
      throw new IllegalArgumentException("Tasa IGV no admitida.");
    return rate;
  }

  public static LineAmounts calculateLineAmounts(DocumentItem item) {
    if (!Set.of("10", "20", "30").contains(item.getTaxAffectationCode()))
      throw new IllegalArgumentException("Afectación tributaria todavía no implementada.");
    BigDecimal quantity = new BigDecimal(item.getQuantity());
    BigDecimal price = new BigDecimal(item.getUnitPrice());
    BigDecimal discount = new BigDecimal(Optional.ofNullable(item.getDiscountAmount()).orElse("0"));
    BigDecimal divisor =
            BigDecimal.ONE.add(taxRate(item).movePointLeft(2));
    BigDecimal gross = quantity.multiply(price).setScale(2, RoundingMode.HALF_UP);
    if (quantity.signum() <= 0
            || price.signum() <= 0
            || discount.signum() < 0
            || discount.compareTo(gross) >= 0)
      throw new IllegalArgumentException(
              "Cantidad/precio positivos y descuento menor al importe de la línea.");
    BigDecimal total = gross.subtract(discount).setScale(2, RoundingMode.HALF_UP);
    BigDecimal baseBeforeDiscount = gross.divide(divisor, 2, RoundingMode.HALF_UP);
    BigDecimal base = total.divide(divisor, 2, RoundingMode.HALF_UP);
    BigDecimal tax = total.subtract(base);
    BigDecimal bagTax = item.getIcbperQuantity() == null ? BigDecimal.ZERO :
            new BigDecimal(item.getIcbperUnitAmount()).multiply(BigDecimal.valueOf(item.getIcbperQuantity())).setScale(2, RoundingMode.HALF_UP);
    total = total.add(bagTax);
    if (item.getCreditAllocation() != null) {
      if (item.getOriginalLineId() == null)
        throw new IllegalArgumentException("La distribución de devolución requiere una línea original.");
      var allocated = item.getCreditAllocation();
      if (allocated.icbper() != null) bagTax = new BigDecimal(allocated.icbper()).setScale(2, RoundingMode.UNNECESSARY);
      base = new BigDecimal(allocated.base()).setScale(2, RoundingMode.UNNECESSARY);
      tax = new BigDecimal(allocated.tax()).setScale(2, RoundingMode.UNNECESSARY);
      total = new BigDecimal(allocated.total()).setScale(2, RoundingMode.UNNECESSARY);
      if (bagTax.signum() < 0 || base.signum() < 0 || tax.signum() < 0 || total.signum() < 0 || base.add(tax).add(bagTax).compareTo(total) != 0)
        throw new IllegalArgumentException("Distribución de devolución incoherente.");
    }
    return new LineAmounts(
            item.getTaxAffectationCode(),
            base,
            tax,
            total,
            price.divide(divisor, 10, RoundingMode.HALF_UP),
            baseBeforeDiscount,
            baseBeforeDiscount.subtract(base),
            discount, bagTax);
  }
}
