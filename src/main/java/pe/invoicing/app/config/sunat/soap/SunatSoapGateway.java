package pe.invoicing.app.config.sunat.soap;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import pe.invoicing.app.config.sunat.credentials.SunatEmissionContext;
import pe.invoicing.app.config.sunat.properties.SunatProperties;
import pe.invoicing.app.config.sunat.xml.SunatXml;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.SunatSendResult;
import pe.invoicing.app.service.document.models.SunatStatus;
import pe.invoicing.app.service.document.port.SunatGateway;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Service
@RequiredArgsConstructor
public class SunatSoapGateway implements SunatGateway {
  private final SunatXml xml;
  private final SunatEmissionContext context;
  private final SunatProperties properties;
  private final org.springframework.web.client.RestClient client;

  private static String payload(String filename, byte[] xml) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(output)) {
        zip.putNextEntry(new ZipEntry(filename + ".xml"));
        zip.write(xml);
        zip.closeEntry();
      }
      return "<fileName>"
              + escape(filename)
              + ".zip</fileName><contentFile>"
              + Base64.getEncoder().encodeToString(output.toByteArray())
              + "</contentFile>";
    } catch (IOException exception) {
      throw new IllegalStateException("No se pudo comprimir el XML firmado.", exception);
    }
  }

  private static Optional<String> text(Document document, String name) {
    NodeList nodes = document.getElementsByTagNameNS("*", name);
    if (nodes.getLength() == 0) return Optional.empty();
    return Optional.of(nodes.item(0).getTextContent().strip()).filter(value -> !value.isBlank());
  }

  private static String escape(String value) {
    return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
  }

  private static Document parse(byte[] bytes)
          throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
  }

  private static java.util.List<org.w3c.dom.Element> children(org.w3c.dom.Element parent, String localName) {
    java.util.List<org.w3c.dom.Element> result = new java.util.ArrayList<>();
    var nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++)
      if (nodes.item(i) instanceof org.w3c.dom.Element element && localName.equals(element.getLocalName()))
        result.add(element);
    return result;
  }

  @Override
  public void validateSupported(DocumentData document) {
    xml.validateTaxDocument(document);
  }

  @Override
  public void checkReady(String documentNumber) {
    context.signingMaterial(documentNumber);
    context.sunatCredentials(documentNumber);
  }

  @Override
  public byte[] signedXml(DocumentData document, Company company) {
    return xml.buildSignedDocumentXml(
            document, company, context.signingMaterial(document.getDocumentNumber()));
  }

  @Override
  public byte[] signedVoid(
          DocumentData document, Company company, String reference, LocalDate date, String reason) {
    return xml.buildVoid(
            document,
            company,
            reference,
            date,
            reason,
            context.signingMaterial(document.getDocumentNumber()));
  }

  @Override
  public byte[] signedSummary(
          DocumentData document, Company company, String reference, LocalDate date, String condition) {
    return xml.buildSummary(
            document,
            company,
            reference,
            date,
            condition,
            context.signingMaterial(document.getDocumentNumber()));
  }

  @Override
  public SunatSendResult sendBill(DocumentData document, byte[] signedXml) {
    String filename =
            document.getDocumentNumber()
                    + "-"
                    + document.getDocumentType()
                    + "-"
                    + document.getSeries()
                    + "-"
                    + document.getNumber();
    Document response =
            call(
                    document.getDocumentNumber(),
                    properties.emissionEndpoint(),
                    "sendBill",
                    payload(filename, signedXml));
    return receipt(
            text(response, "applicationResponse")
                    .orElseThrow(
                            () ->
                                    new ResponseStatusException(
                                            BAD_GATEWAY,
                                            "SUNAT no devolvió CDR; el resultado del envío no está confirmado.")),
            document.getDocumentNumber(), filename);
  }

  @Override
  public String sendSummary(String documentNumber, String reference, byte[] signedXml) {
    try {
      Document payload = parse(signedXml);
      var children = payload.getDocumentElement().getChildNodes();
      String id = null;
      for (int index = 0; index < children.getLength(); index++)
        if ("ID".equals(children.item(index).getLocalName())) id = children.item(index).getTextContent();
      if (!reference.equals(id))
        throw new ResponseStatusException(BAD_GATEWAY, "La referencia no coincide con el XML firmado.");
    } catch (IOException | SAXException | ParserConfigurationException exception) {
      throw new ResponseStatusException(BAD_GATEWAY, "El resumen firmado no es XML válido.");
    }
    Document response =
            call(
                    documentNumber,
                    properties.emissionEndpoint(),
                    "sendSummary",
                    payload(documentNumber + "-" + reference, signedXml));
    return text(response, "ticket")
            .orElseThrow(
                    () ->
                            new ResponseStatusException(
                                    BAD_GATEWAY,
                                    "SUNAT no devolvió ticket para el resumen; resultado no confirmado."));
  }

  @Override
  public SunatStatus getSummaryStatus(String documentNumber, String ticket, String reference) {
    Document response =
            call(
                    documentNumber,
                    properties.emissionEndpoint(),
                    "getStatus",
                    "<ticket>" + escape(ticket) + "</ticket>");
    return new SunatStatus(
            text(response, "statusCode").orElse("UNKNOWN"),
            text(response, "content").map(value -> receipt(value, documentNumber, reference)));
  }

  @Override
  public SunatStatus consultBill(DocumentData document) {
    if (!properties.cdrConsultationAvailable())
      throw new ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT,
              "La consulta getStatusCdr no está disponible en el servicio beta de envío. "
                      + "El comprobante conserva su estado; requiere conciliación manual. No se reenvió.");
    String query =
            "<rucComprobante>"
                    + document.getDocumentNumber()
                    + "</rucComprobante><tipoComprobante>"
                    + document.getDocumentType()
                    + "</tipoComprobante><serieComprobante>"
                    + document.getSeries()
                    + "</serieComprobante><numeroComprobante>"
                    + document.getNumber()
                    + "</numeroComprobante>";
    Document response =
            call(
                    document.getDocumentNumber(), properties.consultationEndpoint(), "getStatusCdr", query);
    return new SunatStatus(
            text(response, "statusCode").orElse("UNKNOWN"),
            text(response, "content").map(value -> receipt(value, document.getDocumentNumber(),
                    document.getDocumentNumber() + "-" + document.getDocumentType() + "-"
                            + document.getSeries() + "-" + document.getNumber())));
  }

  private Document call(String documentNumber, URI endpoint, String operation, String body) {
    String[] credentials = context.sunatCredentials(documentNumber);
    String request =
            """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ser="%s"
                      xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                      <soapenv:Header><wsse:Security><wsse:UsernameToken><wsse:Username>%s</wsse:Username>
                      <wsse:Password>%s</wsse:Password></wsse:UsernameToken></wsse:Security></soapenv:Header>
                      <soapenv:Body><ser:%s>%s</ser:%s></soapenv:Body>
                    </soapenv:Envelope>
                    """
                    .formatted(
                            "http://service.sunat.gob.pe",
                            escape(credentials[0]),
                            escape(credentials[1]),
                            operation,
                            body,
                            operation);
    try {
      return client.post().uri(endpoint).header("Content-Type", "text/xml; charset=UTF-8")
              .header("SOAPAction", "\"\"").body(request).exchange((httpRequest, response) -> {
                byte[] bytes = response.getBody().readNBytes(10_000_001);
                if (bytes.length > 10_000_000) throw new IOException("Respuesta excede el límite de tamaño");
                try {
                  Document payload = parse(bytes);
                  if (payload.getElementsByTagNameNS("*", "Fault").getLength() > 0) {
                    String code = text(payload, "faultcode").orElse("SOAP_FAULT");
                    String detail = text(payload, "faultstring").orElse("Error SOAP sin descripción")
                            .replace(credentials[0], "[usuario]").replace(credentials[1], "[credencial]")
                            .replaceAll("[\r\n]", " ");
                    throw new pe.invoicing.app.service.document.models.SunatFaultException(
                            code, detail.substring(0, Math.min(detail.length(), 500)));
                  }
                  if (!response.getStatusCode().is2xxSuccessful())
                    throw new IOException("HTTP " + response.getStatusCode().value());
                  return payload;
                } catch (SAXException | ParserConfigurationException exception) {
                  throw new ResponseStatusException(BAD_GATEWAY, "SUNAT devolvió una respuesta XML inválida.", exception);
                }
              });
    } catch (org.springframework.web.client.RestClientException exception) {
      throw new ResponseStatusException(BAD_GATEWAY,
              "No se confirmó la respuesta de SUNAT. Consulta el estado; no generes otra emisión.", exception);
    }
  }

  private SunatSendResult receipt(String encoded, String documentNumber, String reference) {
    try {
      byte[] zipped = Base64.getMimeDecoder().decode(encoded);
      SunatSendResult result = null;
      try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipped))) {
        for (ZipEntry entry = input.getNextEntry();
             Objects.nonNull(entry);
             entry = input.getNextEntry()) {
          if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".xml"))
            continue;
          if (result != null) throw new IOException("CDR con múltiples XML ambiguos");
          byte[] bytes = input.readNBytes(5_000_001);
          if (bytes.length > 5_000_000) throw new IOException("CDR demasiado grande");
          Document cdr = parse(bytes);
          if (cdr.getElementsByTagNameNS("*", "DocumentResponse").getLength() != 1
                  || cdr.getElementsByTagNameNS("*", "ReferenceID").getLength() != 1
                  || cdr.getElementsByTagNameNS("*", "ResponseCode").getLength() != 1)
            throw new IOException("Respuesta CDR ambigua");
          String actual = text(cdr, "ReferenceID").orElseThrow(() -> new IOException("CDR sin referencia"));
          String shortReference = reference.replaceFirst("^" + documentNumber + "-(01|03|07|08)-", "");
          if (!actual.equals(reference) && !actual.equals(shortReference)
                  && !actual.equals(documentNumber + "-" + reference))
            throw new IOException("El CDR pertenece a otra comunicación");
          NodeList references = cdr.getElementsByTagNameNS("*", "DocumentReference");
          if (references.getLength() != 1) throw new IOException("CDR sin documento correlacionable");
          NodeList ids = ((org.w3c.dom.Element) references.item(0)).getElementsByTagNameNS("*", "ID");
          if (ids.getLength() != 1 || !java.util.List.of(reference, shortReference, documentNumber + "-" + reference)
                  .contains(ids.item(0).getTextContent().strip()))
            throw new IOException("Referencia CDR contradictoria");
          NodeList receivers = cdr.getElementsByTagNameNS("*", "ReceiverParty");
          if (receivers.getLength() != 1) throw new IOException("CDR sin receptor único");
          var parties = children((org.w3c.dom.Element) receivers.item(0), "PartyIdentification");
          if (parties.size() != 1) throw new IOException("CDR sin identificación única del receptor");
          var receiverIds = children(parties.getFirst(), "ID");
          if (receiverIds.size() != 1 || !receiverIds.getFirst().getTextContent().strip().equals(documentNumber))
            throw new IOException("El CDR pertenece a otro emisor");
          String code =
                  text(cdr, "ResponseCode").orElseThrow(() -> new IOException("CDR sin código"));
          if (!code.matches("[0-9]{1,4}")) throw new IOException("Código CDR inválido");
          NodeList notes = cdr.getElementsByTagNameNS("*", "Note");
          List<String> observations =
                  IntStream.range(0, notes.getLength())
                          .mapToObj(index -> notes.item(index).getTextContent().strip())
                          .toList();
          result = new SunatSendResult(
                  code, text(cdr, "Description").orElse("Resultado SUNAT"), observations, zipped);
        }
      }
      if (result == null) throw new IOException("CDR sin XML");
      return result;
    } catch (IOException
             | SAXException
             | ParserConfigurationException
             | IllegalArgumentException exception) {
      throw new ResponseStatusException(
              BAD_GATEWAY, "La respuesta CDR no es válida; el resultado no está confirmado.", exception);
    }
  }
}
