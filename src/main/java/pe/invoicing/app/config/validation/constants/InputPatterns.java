package pe.invoicing.app.config.validation.constants;

import lombok.experimental.UtilityClass;

@UtilityClass
public class InputPatterns {

  public static final String PDF_FORMAT = "A4|TICKET_48|TICKET_50|TICKET_54|TICKET_55|TICKET_58|TICKET_80";
  public static final String TAX_NUMBER = "^[0-9]{11}$";
  public static final String TAXPAYER_TYPE = "^(?:NATURAL|LEGAL)$";
  public static final String TWO_DIGIT_CODE = "^[0-9]{2}$";
  public static final String GEOGRAPHIC_CODE = "^[0-9]{6}$";
  public static final String COUNTRY = "^PE$";
  public static final String IDENTITY_DOCUMENT_TYPE = "(?:0|1|4|6|7)";
  public static final String UNIT = "^[A-Z0-9]{2,3}$";
  public static final String DECIMAL = "^[0-9]{1,12}(?:\\.[0-9]{1,6})?$";
  public static final String MONEY = "^[0-9]{1,12}(?:\\.[0-9]{1,2})?$";

  public static final String SERIES = "^[A-Z0-9]{4}$";
  public static final String SALES_DOCUMENT = "01|03";
  public static final String DOCUMENT_TYPES = "01|03|07|08|09";
  public static final String DOCUMENT_STATUSES =
          "PENDING|CREATED|SIGNED|ACCEPTED|REJECTED|ANNULLED";
  public static final String CURRENCY = "PEN|USD|EUR";
  public static final String DOMESTIC_OPERATION = "(?:0101|1001)";

  public static final String TAX_AFFECTATION = "10|20|30";
}
