package pe.invoicing.app.config.validation.constants;

import lombok.experimental.UtilityClass;

@UtilityClass
public class InputMessages {
  public static final String POSITIVE = "El importe o cantidad debe ser mayor que cero.";
  public static final String IDENTITY = "El número no corresponde al tipo de documento.";
  public static final String TAX_NUMBER = "Indica un RUC válido de 11 dígitos.";
  public static final String EMAIL_REQUIRED = "Ingresa un correo electrónico para continuar.";
  public static final String EMAIL = "Ingresa un correo electrónico válido.";
  public static final String EMAIL_LENGTH = "El correo electrónico es demasiado largo.";
  public static final String PASSWORD_REQUIRED = "Ingresa tu contraseña para continuar.";
  public static final String PASSWORD_LENGTH =
          "La contraseña debe tener entre 12 y 128 caracteres.";
  public static final String REQUIRED = "Este dato es necesario para continuar.";
  public static final String ADDRESS_LENGTH = "La dirección debe tener hasta 250 caracteres.";

  public static final String QUANTITY_REQUIRED = "Indica la cantidad del producto o servicio.";
  public static final String QUANTITY = "Revisa el formato de la cantidad.";
  public static final String PRICE_REQUIRED = "Indica el precio del producto o servicio.";
  public static final String PRICE = "Revisa el formato del precio.";
  public static final String DISCOUNT = "Revisa el importe del descuento.";
  public static final String SALES_DOCUMENT =
          "Emite factura 01 o boleta 03; las notas de anulación se generan mediante /cancel.";
  public static final String INSTALLMENT_REQUIRED = "Indica el importe de la cuota.";
  public static final String INSTALLMENT_AMOUNT = "Revisa el importe de la cuota.";
  public static final String DOCUMENT_TYPE = "Selecciona un tipo de comprobante válido.";
  public static final String SERIES = "La serie debe tener cuatro letras o números.";

  public static final String FORMAT = "El formato de este dato no es válido.";
  public static final String SIZE = "El tamaño debe estar entre {min} y {max}.";
  public static final String MINIMUM = "El valor mínimo es {value}.";
  public static final String MAXIMUM = "El valor máximo es {value}.";

  public static final String INVALID_PAYMENT_AND_CUSTOMER =
          "La factura requiere cliente y las ventas al crédito deben incluir monto pendiente y cuotas.";
  public static final String CURRENCY = "Usa una moneda admitida: PEN, USD o EUR.";
  public static final String TAX_AFFECTATION =
          "Usa afectación 10 (gravada), 20 (exonerada) o 30 (inafecta).";
  public static final String PAYMENT = "Usa CASH (contado) o CREDIT (crédito).";
}
