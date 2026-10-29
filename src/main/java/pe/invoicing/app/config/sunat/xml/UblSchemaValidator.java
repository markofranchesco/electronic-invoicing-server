package pe.invoicing.app.config.sunat.xml;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Component
public class UblSchemaValidator {
  private final Map<String, Schema> schemas;

  public UblSchemaValidator() throws SAXException, IOException {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar,nested");
    schemas =
            Map.of(
                    "INVOICE",
                    factory.newSchema(new ClassPathResource("ubl21/maindoc/UBL-Invoice-2.1.xsd").getURL()),
                    "CREDIT",
                    factory.newSchema(
                            new ClassPathResource("ubl21/maindoc/UBL-CreditNote-2.1.xsd").getURL()),
                    "DEBIT",
                    factory.newSchema(new ClassPathResource("ubl21/maindoc/UBL-DebitNote-2.1.xsd").getURL()),
                    "RA",
                    factory.newSchema(
                            new ClassPathResource("ubl20/maindoc/UBLPE-VoidedDocuments-1.0.xsd").getURL()),
                    "RC",
                    factory.newSchema(
                            new ClassPathResource("ubl20/maindoc/UBLPE-SummaryDocuments-1.0.xsd").getURL()));
  }

  public void validate(byte[] signed, String kind) {
    try {
      schemas.get(kind).newValidator().validate(new StreamSource(new ByteArrayInputStream(signed)));
    } catch (SAXException | IOException exception) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "El XML no cumple UBL 2.1: " + exception.getMessage());
    }
  }
}
