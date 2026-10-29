package pe.invoicing.app.config.sunat.xml;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import pe.invoicing.app.config.sunat.models.SigningMaterial;
import pe.invoicing.app.service.company.models.Address;
import pe.invoicing.app.service.company.models.Company;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentItem;
import pe.invoicing.app.service.document.models.Installment;
import pe.invoicing.app.service.document.models.LineAmounts;

import javax.xml.XMLConstants;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT;

@Component
@RequiredArgsConstructor
public class SunatXml {
  private static final String INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
  private static final String CBC =
          "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
  private static final String CAC =
          "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
  private static final String EXT =
          "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2";
  private static final String DS = "http://www.w3.org/2000/09/xmldsig#";
  // Unidades publicadas en la guía SUNAT UBL 2.1, catálogo 03 (alcance de esta versión).
  private static final Set<String> SUPPORTED_UNITS = Set.of(
          "BJ", "BG", "BO", "BX", "CT", "CY", "CJ", "GRM", "SET", "KGM", "KTM", "KT",
          "CA", "LBR", "LTR", "MTR", "MLL", "UM", "NIU", "ZZ", "CS", "TNE", "DAY");
  private final UblSchemaValidator schemaValidator;

  private static void appendParty(
          Document xml,
          Element parent,
          String doc,
          String type,
          String name,
          Address addr,
          boolean supplier,
          String establishmentCode) {
    Element p = appendElement(xml, parent, CAC, "cac:Party");
    Element id = appendElement(xml, p, CAC, "cac:PartyIdentification");
    Element idVal = appendTextElement(xml, id, CBC, "cbc:ID", doc);
    idVal.setAttribute("schemeID", type);
    idVal.setAttribute("schemeName", "Documento de Identidad");
    idVal.setAttribute("schemeAgencyName", "PE:SUNAT");
    Element legal = appendElement(xml, p, CAC, "cac:PartyLegalEntity");
    appendTextElement(xml, legal, CBC, "cbc:RegistrationName", name);
    if (supplier) {
      Element address = appendElement(xml, legal, CAC, "cac:RegistrationAddress");
      appendTextElement(
              xml, address, CBC, "cbc:ID", Optional.ofNullable(addr.getGeographicCode()).orElse(""));
      appendTextElement(xml, address, CBC, "cbc:AddressTypeCode", establishmentCode);
      appendTextElement(
              xml, address, CBC, "cbc:CityName", Optional.ofNullable(addr.getProvince()).orElse(""));
      appendTextElement(
              xml,
              address,
              CBC,
              "cbc:CountrySubentity",
              Optional.ofNullable(addr.getDepartment()).orElse(""));
      appendTextElement(
              xml, address, CBC, "cbc:District", Optional.ofNullable(addr.getDistrict()).orElse(""));
      Element line = appendElement(xml, address, CAC, "cac:AddressLine");
      appendTextElement(
              xml, line, CBC, "cbc:Line", Optional.ofNullable(addr.getStreet()).orElse(""));
      Element country = appendElement(xml, address, CAC, "cac:Country");
      appendTextElement(xml, country, CBC, "cbc:IdentificationCode", "PE");
    }
  }

  private static void appendTaxSubtotal(
          Document xml, Element parent, BigDecimal net, BigDecimal tax, String affectation, BigDecimal rate) {
    Element subtotal = appendElement(xml, parent, CAC, "cac:TaxSubtotal");
    appendMoneyElement(xml, subtotal, "cbc:TaxableAmount", net);
    appendMoneyElement(xml, subtotal, "cbc:TaxAmount", tax);
    Element category = appendElement(xml, subtotal, CAC, "cac:TaxCategory");
    appendTextElement(
            xml,
            category,
            CBC,
            "cbc:ID",
            switch (affectation) {
              case "10" -> "S";
              case "20" -> "E";
              default -> "O";
            });
    appendTextElement(
            xml, category, CBC, "cbc:Percent", rate.setScale(2, RoundingMode.HALF_UP).toPlainString());
    appendTextElement(xml, category, CBC, "cbc:TaxExemptionReasonCode", affectation);
    Element scheme = appendElement(xml, category, CAC, "cac:TaxScheme");
    appendTextElement(
            xml,
            scheme,
            CBC,
            "cbc:ID",
            switch (affectation) {
              case "10" -> "1000";
              case "20" -> "9997";
              default -> "9998";
            });
    appendTextElement(
            xml,
            scheme,
            CBC,
            "cbc:Name",
            switch (affectation) {
              case "10" -> "IGV";
              case "20" -> "EXO";
              default -> "INA";
            });
    appendTextElement(
            xml, scheme, CBC, "cbc:TaxTypeCode", "30".equals(affectation) ? "FRE" : "VAT");
  }

  private static Element appendElement(Document d, Element p, String ns, String qualified) {
    Element child = d.createElementNS(ns, qualified);
    p.appendChild(child);
    return child;
  }

  private static Element appendTextElement(
          Document d, Element parent, String ns, String qname, String text) {
    Element child = appendElement(d, parent, ns, qname);
    child.setTextContent(text);
    return child;
  }

  private static void appendMoneyElement(
          Document d, Element parent, String qname, BigDecimal amount) {
    Element money =
            appendTextElement(
                    d, parent, CBC, qname, amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
    money.setAttribute(
            "currencyID",
            d.getDocumentElement()
                    .getElementsByTagNameNS(CBC, "DocumentCurrencyCode")
                    .item(0)
                    .getTextContent());
  }

  private static void summaryMoney(
          Document xml, Element parent, String namespace, String name, String value, String currency) {
    appendTextElement(
            xml,
            parent,
            namespace,
            name,
            new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).toPlainString())
            .setAttribute("currencyID", currency);
  }

  private static byte[] signDocument(Document xml, Element content, SigningMaterial signing)
          throws GeneralSecurityException,
          MarshalException,
          XMLSignatureException,
          TransformerException {
    XMLSignatureFactory ds = XMLSignatureFactory.getInstance("DOM");
    Reference ref =
            ds.newReference(
                    "",
                    ds.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(ds.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                    null,
                    null);
    SignedInfo info =
            ds.newSignedInfo(
                    ds.newCanonicalizationMethod(
                            CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                    ds.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                    List.of(ref));
    KeyInfoFactory keys = ds.getKeyInfoFactory();
    X509Data x509 = keys.newX509Data(List.of(signing.certificate()));
    KeyInfo keyInfo = keys.newKeyInfo(List.of(x509));
    DOMSignContext ctx = new DOMSignContext(signing.key(), content);
    ctx.setDefaultNamespacePrefix("ds");
    ds.newXMLSignature(info, keyInfo, null, "SIGN-1", null).sign(ctx);
    TransformerFactory outFactory = TransformerFactory.newInstance();
    outFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Transformer transformer = outFactory.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transformer.transform(new DOMSource(xml), new StreamResult(out));
    return out.toByteArray();
  }

  private static BigDecimal documentTaxRate(DocumentData d) {
    List<BigDecimal> rates = d.getItems().stream()
            .filter(item -> "10".equals(item.getTaxAffectationCode()))
            .map(LineAmounts::taxRate).map(BigDecimal::stripTrailingZeros).distinct().toList();
    if (rates.size() > 1)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT,
              "SUNAT exige la misma tasa IGV en todas las líneas gravadas (3462).");
    return rates.isEmpty() ? new BigDecimal("18.00") : rates.getFirst();
  }

  public void validateTaxDocument(DocumentData d) {
    documentTaxRate(d);
    if (d.getNumber() == null || d.getNumber() < 1 || d.getNumber() > 99999999
            || d.getSeries() == null || !d.getSeries().matches("[FB][A-Z0-9]{3}"))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Numeración SUNAT inválida.");
    if (d.getItems() == null || d.getItems().isEmpty() || d.getItems().size() > 500)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Detalle SUNAT inválido.");
    List<LineAmounts> amounts = d.getItems().stream().map(LineAmounts::calculateLineAmounts).toList();
    BigDecimal total = amounts.stream().map(LineAmounts::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal tax = amounts.stream().map(LineAmounts::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal icbper = amounts.stream().map(LineAmounts::icbper).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (d.getTotals() != null && icbper.compareTo(new BigDecimal(Optional.ofNullable(d.getTotals().getIcbperAmount()).orElse("0"))) != 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "ICBPER no coincide con el detalle.");
    if (d.getTotals() == null || total.compareTo(new BigDecimal(d.getTotals().getTotalAmount())) != 0
            || tax.compareTo(new BigDecimal(d.getTotals().getIgvAmount())) != 0)
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Los totales no coinciden con el detalle.");
    List<String> bases = List.of(d.getTotals().getTaxableAmount(), d.getTotals().getExemptAmount(),
            d.getTotals().getUnaffectedAmount());
    List<String> affectations = List.of("10", "20", "30");
    for (int index = 0; index < affectations.size(); index++) {
      String affectation = affectations.get(index);
      BigDecimal base = amounts.stream().filter(a -> affectation.equals(a.affectation()))
              .map(LineAmounts::base).reduce(BigDecimal.ZERO, BigDecimal::add);
      if (base.compareTo(new BigDecimal(bases.get(index))) != 0)
        throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "La base tributaria no coincide con el detalle.");
    }
    for (DocumentItem item : d.getItems()) {
      if (!SUPPORTED_UNITS.contains(item.getUnitCode()))
        throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Unidad de medida no admitida del catálogo 03.");
      if (item.getDescription() == null || item.getDescription().isBlank()
              || item.getDescription().length() > 500 || item.getDescription().matches("(?s).*?[\\r\\n\\t].*"))
        throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Descripción de ítem inválida.");
    }
    if (Set.of("07", "08").contains(d.getDocumentType()) && (d.getNote() == null
            || d.getNote().getOriginalDocument() == null
            || !("08".equals(d.getDocumentType()) ? Set.of("01", "02") : Set.of("01", "02", "03", "04", "05", "06", "07", "09", "13")).contains(d.getNote().getReasonCode())
            || !Set.of("01", "03").contains(d.getNote().getOriginalDocument().getDocumentType())))
      throw new ResponseStatusException(UNPROCESSABLE_CONTENT, "Referencia o motivo de nota no admitido.");

    if (!Set.of("01", "03", "07", "08").contains(d.getDocumentType()))
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "Esta versión emite facturas y notas de crédito de anulación.");
    if (!Set.of("0101", "1001").contains(d.getOperationType()))
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT,
              "El generador XML no soporta todavía este tipo de operación tributaria.");
    if (d.getSeries().startsWith("F") && !"6".equals(d.getCustomer().getDocumentType()))
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "La factura requiere TAX_NUMBER del comprador.");
  }

  private void appendBagTax(Document xml, Element tax, BigDecimal amount, DocumentItem item) {
    Element subtotal = appendElement(xml, tax, CAC, "cac:TaxSubtotal");
    appendMoneyElement(xml, subtotal, "cbc:TaxAmount", amount);
    if (item != null) appendTextElement(xml, subtotal, CBC, "cbc:BaseUnitMeasure", item.getIcbperQuantity().toString()).setAttribute("unitCode", "NIU");
    Element category = appendElement(xml, subtotal, CAC, "cac:TaxCategory");
    if (item != null) {
      Element unit = appendTextElement(xml, category, CBC, "cbc:PerUnitAmount", item.getIcbperUnitAmount());
      unit.setAttribute("currencyID", xml.getElementsByTagNameNS(CBC, "DocumentCurrencyCode").item(0).getTextContent());
    }
    Element scheme = appendElement(xml, category, CAC, "cac:TaxScheme");
    appendTextElement(xml, scheme, CBC, "cbc:ID", "7152");
    appendTextElement(xml, scheme, CBC, "cbc:Name", "ICBPER");
    appendTextElement(xml, scheme, CBC, "cbc:TaxTypeCode", "OTH");
  }

  private void appendBagScheme(Document xml, Element subtotal) {
    Element category = appendElement(xml, subtotal, CAC, "cac:TaxCategory");
    Element scheme = appendElement(xml, category, CAC, "cac:TaxScheme");
    appendTextElement(xml, scheme, CBC, "cbc:ID", "7152");
    appendTextElement(xml, scheme, CBC, "cbc:Name", "ICBPER");
    appendTextElement(xml, scheme, CBC, "cbc:TaxTypeCode", "OTH");
  }

  public byte[] buildSignedDocumentXml(DocumentData d, Company company, SigningMaterial signing) {
    validateTaxDocument(d);
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      Document xml = factory.newDocumentBuilder().newDocument();
      boolean credit = "07".equals(d.getDocumentType());
      boolean debit = "08".equals(d.getDocumentType());
      boolean note = credit || debit;
      String namespace =
              credit ? "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2"
                      : debit ? "urn:oasis:names:specification:ubl:schema:xsd:DebitNote-2" : INVOICE;
      Element root = xml.createElementNS(namespace, credit ? "CreditNote" : debit ? "DebitNote" : "Invoice");
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", namespace);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cac", CAC);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cbc", CBC);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ext", EXT);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ds", DS);
      xml.appendChild(root);
      Element exts = appendElement(xml, root, EXT, "ext:UBLExtensions");
      Element ext = appendElement(xml, exts, EXT, "ext:UBLExtension");
      Element content = appendElement(xml, ext, EXT, "ext:ExtensionContent");
      appendTextElement(xml, root, CBC, "cbc:UBLVersionID", "2.1");
      appendTextElement(xml, root, CBC, "cbc:CustomizationID", "2.0");
      Element profile = appendTextElement(xml, root, CBC, "cbc:ProfileID", d.getOperationType());
      profile.setAttribute("schemeName", "SUNAT:Identificador de Tipo de Operación");
      profile.setAttribute("schemeAgencyName", "PE:SUNAT");
      profile.setAttribute("schemeURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo17");
      appendTextElement(xml, root, CBC, "cbc:ID", d.getSeries() + "-" + d.getNumber());
      appendTextElement(xml, root, CBC, "cbc:IssueDate", d.getIssueDate().toString());
      if (!note) {
        Element code =
                appendTextElement(xml, root, CBC, "cbc:InvoiceTypeCode", d.getDocumentType());
        code.setAttribute("listID", d.getOperationType());
        code.setAttribute("listAgencyName", "PE:SUNAT");
        code.setAttribute("listName", "Tipo de Documento");
        code.setAttribute("listURI", "urn:pe:gob:sunat:cpe:see:gem:catalogos:catalogo01");
      }
      if (d.getDetraction() != null) appendTextElement(xml, root, CBC, "cbc:Note",
              "Operación sujeta al Sistema de Pago de Obligaciones Tributarias con el Gobierno Central").setAttribute("languageLocaleID", "2006");
      appendTextElement(xml, root, CBC, "cbc:DocumentCurrencyCode", d.getCurrency());
      if (note) {
        String reference =
                d.getNote().getOriginalDocument().getSeries()
                        + "-"
                        + d.getNote().getOriginalDocument().getNumber();
        Element discrepancy = appendElement(xml, root, CAC, "cac:DiscrepancyResponse");
        appendTextElement(xml, discrepancy, CBC, "cbc:ReferenceID", reference);
        appendTextElement(xml, discrepancy, CBC, "cbc:ResponseCode", d.getNote().getReasonCode());
        appendTextElement(xml, discrepancy, CBC, "cbc:Description", d.getNote().getReason());
        Element billing = appendElement(xml, root, CAC, "cac:BillingReference");
        Element original = appendElement(xml, billing, CAC, "cac:InvoiceDocumentReference");
        appendTextElement(xml, original, CBC, "cbc:ID", reference);
        appendTextElement(
                xml,
                original,
                CBC,
                "cbc:DocumentTypeCode",
                d.getNote().getOriginalDocument().getDocumentType());
      }

      Element sig = appendElement(xml, root, CAC, "cac:Signature");
      appendTextElement(xml, sig, CBC, "cbc:ID", "SIGN-1");
      Element signatory = appendElement(xml, sig, CAC, "cac:SignatoryParty");
      Element signId = appendElement(xml, signatory, CAC, "cac:PartyIdentification");
      appendTextElement(xml, signId, CBC, "cbc:ID", company.getDocumentNumber());
      Element signName = appendElement(xml, signatory, CAC, "cac:PartyName");
      appendTextElement(xml, signName, CBC, "cbc:Name", d.getIssuerLegalName() == null ? company.getLegalName() : d.getIssuerLegalName());
      Element digital = appendElement(xml, sig, CAC, "cac:DigitalSignatureAttachment");
      Element external = appendElement(xml, digital, CAC, "cac:ExternalReference");
      appendTextElement(xml, external, CBC, "cbc:URI", "#SIGN-1");

      Element supplier = appendElement(xml, root, CAC, "cac:AccountingSupplierParty");
      appendParty(
              xml,
              supplier,
              company.getDocumentNumber(),
              "6",
              d.getIssuerLegalName() == null ? company.getLegalName() : d.getIssuerLegalName(),
              d.getIssuerAddress() != null ? d.getIssuerAddress()
                      : d.getEstablishment() == null ? company.getAddress() : d.getEstablishment().address(),
              true,
              d.getEstablishment() == null ? "0000" : d.getEstablishment().code());
      Element customer = appendElement(xml, root, CAC, "cac:AccountingCustomerParty");
      appendParty(
              xml,
              customer,
              d.getCustomer().getDocumentNumber(),
              d.getCustomer().getDocumentType(),
              d.getCustomer().getName(),
              null,
              false,
              "");

      if (d.getDetraction() != null) {
        var detraction = d.getDetraction();
        Element means = appendElement(xml, root, CAC, "cac:PaymentMeans");
        appendTextElement(xml, means, CBC, "cbc:ID", "Detraccion");
        appendTextElement(xml, means, CBC, "cbc:PaymentMeansCode", "001");
        Element account = appendElement(xml, means, CAC, "cac:PayeeFinancialAccount");
        appendTextElement(xml, account, CBC, "cbc:ID", detraction.bankAccount());
        Element terms = appendElement(xml, root, CAC, "cac:PaymentTerms");
        appendTextElement(xml, terms, CBC, "cbc:ID", "Detraccion");
        appendTextElement(xml, terms, CBC, "cbc:PaymentMeansID", detraction.serviceCode());
        appendTextElement(xml, terms, CBC, "cbc:PaymentPercent", detraction.rate());
        appendTextElement(xml, terms, CBC, "cbc:Amount", detraction.amountPen(d.getTotals().getTotalAmount(), d.getCurrency(), d.getExchangeRate()).toPlainString())
                .setAttribute("currencyID", "PEN");
      }
      if ("01".equals(d.getDocumentType()) || "07".equals(d.getDocumentType()) && "13".equals(d.getNote().getReasonCode())) {
        Element terms = appendElement(xml, root, CAC, "cac:PaymentTerms");
        appendTextElement(xml, terms, CBC, "cbc:ID", "FormaPago");
        appendTextElement(
                xml,
                terms,
                CBC,
                "cbc:PaymentMeansID",
                "CREDIT".equals(d.getPayment().getType()) ? "Credito" : "Contado");
        if ("CREDIT".equals(d.getPayment().getType())) {
          appendMoneyElement(
                  xml, terms, "cbc:Amount", new BigDecimal(d.getPayment().getPendingAmount()));
          for (int index = 0; index < d.getPayment().getInstallments().size(); index++) {
            Installment installment = d.getPayment().getInstallments().get(index);
            Element quota = appendElement(xml, root, CAC, "cac:PaymentTerms");
            appendTextElement(xml, quota, CBC, "cbc:ID", "FormaPago");
            appendTextElement(
                    xml, quota, CBC, "cbc:PaymentMeansID", "Cuota%03d".formatted(index + 1));
            appendMoneyElement(xml, quota, "cbc:Amount", new BigDecimal(installment.getAmount()));
            appendTextElement(
                    xml, quota, CBC, "cbc:PaymentDueDate", installment.getDueDate().toString());
          }
        }
      }
      Optional.ofNullable(d.getExchangeRate())
              .ifPresent(
                      rate -> {
                        Element exchange = appendElement(xml, root, CAC, "cac:TaxExchangeRate");
                        appendTextElement(xml, exchange, CBC, "cbc:SourceCurrencyCode", d.getCurrency());
                        appendTextElement(xml, exchange, CBC, "cbc:TargetCurrencyCode", "PEN");
                        appendTextElement(xml, exchange, CBC, "cbc:CalculationRate", rate);
                        appendTextElement(xml, exchange, CBC, "cbc:Date", d.getIssueDate().toString());
                      });
      List<LineAmounts> amounts =
              d.getItems().stream().map(LineAmounts::calculateLineAmounts).toList();
      BigDecimal netTotal =
              amounts.stream().map(LineAmounts::base).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal vatTotal =
              amounts.stream().map(LineAmounts::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal bags = amounts.stream().map(LineAmounts::icbper).reduce(BigDecimal.ZERO, BigDecimal::add);
      Element tax = appendElement(xml, root, CAC, "cac:TaxTotal");
      appendMoneyElement(xml, tax, "cbc:TaxAmount", vatTotal.add(bags));
      for (String affectation : List.of("10", "20", "30")) {
        List<LineAmounts> group =
                amounts.stream().filter(line -> affectation.equals(line.affectation())).toList();
        if (group.isEmpty()) continue;
        appendTaxSubtotal(
                xml,
                tax,
                group.stream().map(LineAmounts::base).reduce(BigDecimal.ZERO, BigDecimal::add),
                group.stream().map(LineAmounts::tax).reduce(BigDecimal.ZERO, BigDecimal::add),
                affectation, "10".equals(affectation) ? documentTaxRate(d) : BigDecimal.ZERO);
      }
      if (bags.signum() > 0) appendBagTax(xml, tax, bags, null);
      Element monetary = appendElement(xml, root, CAC, debit ? "cac:RequestedMonetaryTotal" : "cac:LegalMonetaryTotal");
      appendMoneyElement(xml, monetary, "cbc:LineExtensionAmount", netTotal);
      appendMoneyElement(xml, monetary, "cbc:TaxInclusiveAmount", netTotal.add(vatTotal).add(bags));
      appendMoneyElement(xml, monetary, "cbc:PayableAmount", netTotal.add(vatTotal).add(bags));

      int n = 1;
      for (DocumentItem item : d.getItems()) {
        Element line =
                appendElement(xml, root, CAC, credit ? "cac:CreditNoteLine" : debit ? "cac:DebitNoteLine" : "cac:InvoiceLine");
        appendTextElement(xml, line, CBC, "cbc:ID", Integer.toString(n++));
        Element qty =
                appendTextElement(
                        xml,
                        line,
                        CBC,
                        credit ? "cbc:CreditedQuantity" : debit ? "cbc:DebitedQuantity" : "cbc:InvoicedQuantity",
                        item.getQuantity());
        qty.setAttribute("unitCode", item.getUnitCode());
        LineAmounts amount = LineAmounts.calculateLineAmounts(item);
        BigDecimal net = amount.base();
        BigDecimal vat = amount.tax();
        appendMoneyElement(xml, line, "cbc:LineExtensionAmount", net);
        Element pricing = appendElement(xml, line, CAC, "cac:PricingReference");
        Element alt = appendElement(xml, pricing, CAC, "cac:AlternativeConditionPrice");
        appendTextElement(
                xml,
                alt,
                CBC,
                "cbc:PriceAmount",
                amount
                        .total().subtract(amount.icbper())
                        .divide(new BigDecimal(item.getQuantity()), 10, RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString())
                .setAttribute("currencyID", d.getCurrency());
        appendTextElement(xml, alt, CBC, "cbc:PriceTypeCode", "01");
        Element lineTax = appendElement(xml, line, CAC, "cac:TaxTotal");
        appendMoneyElement(xml, lineTax, "cbc:TaxAmount", vat.add(amount.icbper()));
        appendTaxSubtotal(xml, lineTax, net, vat, item.getTaxAffectationCode(), LineAmounts.taxRate(item));
        if (amount.icbper().signum() > 0) appendBagTax(xml, lineTax, amount.icbper(), item);
        if (!note && amount.discountBase().signum() > 0) {
          Element allowance = appendElement(xml, line, CAC, "cac:AllowanceCharge");
          appendTextElement(xml, allowance, CBC, "cbc:ChargeIndicator", "false");
          appendTextElement(xml, allowance, CBC, "cbc:AllowanceChargeReasonCode", "00");
          appendTextElement(
                  xml,
                  allowance,
                  CBC,
                  "cbc:MultiplierFactorNumeric",
                  amount
                          .discountBase()
                          .divide(amount.baseBeforeDiscount(), 5, RoundingMode.HALF_UP)
                          .toPlainString());
          appendMoneyElement(xml, allowance, "cbc:Amount", amount.discountBase());
          appendMoneyElement(xml, allowance, "cbc:BaseAmount", amount.baseBeforeDiscount());
          line.insertBefore(allowance, lineTax);
        }
        Element details = appendElement(xml, line, CAC, "cac:Item");
        appendTextElement(xml, details, CBC, "cbc:Description", item.getDescription());
        if (Optional.ofNullable(item.getProductCode())
                .filter(code -> !code.isBlank())
                .isPresent()) {
          Element sellerId = appendElement(xml, details, CAC, "cac:SellersItemIdentification");
          appendTextElement(xml, sellerId, CBC, "cbc:ID", item.getProductCode());
        }
        Element price = appendElement(xml, line, CAC, "cac:Price");

        appendTextElement(
                xml,
                price,
                CBC,
                "cbc:PriceAmount",
                (note
                        ? amount
                        .base()
                        .divide(new BigDecimal(item.getQuantity()), 10, RoundingMode.HALF_UP)
                        : amount.unitValue())
                        .stripTrailingZeros()
                        .toPlainString())
                .setAttribute("currencyID", d.getCurrency());
      }
      byte[] signed = signDocument(xml, content, signing);
      schemaValidator.validate(signed, credit ? "CREDIT" : debit ? "DEBIT" : "INVOICE");
      return signed;
    } catch (GeneralSecurityException
             | ParserConfigurationException
             | XMLSignatureException
             | MarshalException
             | TransformerException
             | NumberFormatException ex) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT,
              "No se pudo crear y firmar el XML. Revisa el certificado y los datos del comprobante.");
    }
  }

  public byte[] buildVoid(
          DocumentData document,
          Company company,
          String reference,
          LocalDate date,
          String reason,
          SigningMaterial signing) {
    try {
      String namespace = "urn:sunat:names:specification:ubl:peru:schema:xsd:VoidedDocuments-1";
      String sac = "urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1";
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      Document xml = factory.newDocumentBuilder().newDocument();
      Element root = xml.createElementNS(namespace, "VoidedDocuments");
      xml.appendChild(root);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", namespace);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cac", CAC);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cbc", CBC);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ext", EXT);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:sac", sac);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ds", DS);
      Element extensions = appendElement(xml, root, EXT, "ext:UBLExtensions");
      Element extension = appendElement(xml, extensions, EXT, "ext:UBLExtension");
      Element content = appendElement(xml, extension, EXT, "ext:ExtensionContent");
      appendTextElement(xml, root, CBC, "cbc:UBLVersionID", "2.0");
      appendTextElement(xml, root, CBC, "cbc:CustomizationID", "1.0");
      appendTextElement(xml, root, CBC, "cbc:ID", reference);
      appendTextElement(xml, root, CBC, "cbc:ReferenceDate", document.getIssueDate().toString());
      appendTextElement(xml, root, CBC, "cbc:IssueDate", date.toString());
      Element signature = appendElement(xml, root, CAC, "cac:Signature");
      appendTextElement(xml, signature, CBC, "cbc:ID", "SIGN-1");
      Element signatory = appendElement(xml, signature, CAC, "cac:SignatoryParty");
      Element identification = appendElement(xml, signatory, CAC, "cac:PartyIdentification");
      appendTextElement(xml, identification, CBC, "cbc:ID", company.getDocumentNumber());
      Element attachment = appendElement(xml, signature, CAC, "cac:DigitalSignatureAttachment");
      Element external = appendElement(xml, attachment, CAC, "cac:ExternalReference");
      appendTextElement(xml, external, CBC, "cbc:URI", "#SIGN-1");
      Element supplier = appendElement(xml, root, CAC, "cac:AccountingSupplierParty");
      appendTextElement(
              xml, supplier, CBC, "cbc:CustomerAssignedAccountID", company.getDocumentNumber());
      appendTextElement(xml, supplier, CBC, "cbc:AdditionalAccountID", "6");
      Element party = appendElement(xml, supplier, CAC, "cac:Party");
      Element legal = appendElement(xml, party, CAC, "cac:PartyLegalEntity");
      appendTextElement(xml, legal, CBC, "cbc:RegistrationName", company.getLegalName());
      Element line = appendElement(xml, root, sac, "sac:VoidedDocumentsLine");
      appendTextElement(xml, line, CBC, "cbc:LineID", "1");
      appendTextElement(xml, line, CBC, "cbc:DocumentTypeCode", document.getDocumentType());
      appendTextElement(xml, line, sac, "sac:DocumentSerialID", document.getSeries());
      appendTextElement(xml, line, sac, "sac:DocumentNumberID", document.getNumber().toString());
      appendTextElement(xml, line, sac, "sac:VoidReasonDescription", reason);
      byte[] signed = signDocument(xml, content, signing);
      schemaValidator.validate(signed, "RA");
      return signed;
    } catch (GeneralSecurityException
             | ParserConfigurationException
             | XMLSignatureException
             | MarshalException
             | TransformerException exception) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "No se pudo crear y firmar la comunicación de baja.");
    }
  }

  public byte[] buildSummary(
          DocumentData document,
          Company company,
          String reference,
          LocalDate date,
          String condition,
          SigningMaterial signing) {
    validateTaxDocument(document);
    try {
      String namespace = "urn:sunat:names:specification:ubl:peru:schema:xsd:SummaryDocuments-1";
      String sac = "urn:sunat:names:specification:ubl:peru:schema:xsd:SunatAggregateComponents-1";
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      Document xml = factory.newDocumentBuilder().newDocument();
      Element root = xml.createElementNS(namespace, "SummaryDocuments");
      xml.appendChild(root);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", namespace);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cac", CAC);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cbc", CBC);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ext", EXT);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:sac", sac);
      root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ds", DS);
      Element extensions = appendElement(xml, root, EXT, "ext:UBLExtensions");
      Element extension = appendElement(xml, extensions, EXT, "ext:UBLExtension");
      Element content = appendElement(xml, extension, EXT, "ext:ExtensionContent");
      appendTextElement(xml, root, CBC, "cbc:UBLVersionID", "2.0");
      appendTextElement(xml, root, CBC, "cbc:CustomizationID", "1.1");
      appendTextElement(xml, root, CBC, "cbc:ID", reference);
      appendTextElement(xml, root, CBC, "cbc:ReferenceDate", document.getIssueDate().toString());
      appendTextElement(xml, root, CBC, "cbc:IssueDate", date.toString());
      Element signature = appendElement(xml, root, CAC, "cac:Signature");
      appendTextElement(xml, signature, CBC, "cbc:ID", "SIGN-1");
      Element signatory = appendElement(xml, signature, CAC, "cac:SignatoryParty");
      Element identification = appendElement(xml, signatory, CAC, "cac:PartyIdentification");
      appendTextElement(xml, identification, CBC, "cbc:ID", company.getDocumentNumber());
      Element attachment = appendElement(xml, signature, CAC, "cac:DigitalSignatureAttachment");
      Element external = appendElement(xml, attachment, CAC, "cac:ExternalReference");
      appendTextElement(xml, external, CBC, "cbc:URI", "#SIGN-1");
      Element supplier = appendElement(xml, root, CAC, "cac:AccountingSupplierParty");
      appendTextElement(
              xml, supplier, CBC, "cbc:CustomerAssignedAccountID", company.getDocumentNumber());
      appendTextElement(xml, supplier, CBC, "cbc:AdditionalAccountID", "6");
      Element party = appendElement(xml, supplier, CAC, "cac:Party");
      Element legal = appendElement(xml, party, CAC, "cac:PartyLegalEntity");
      appendTextElement(xml, legal, CBC, "cbc:RegistrationName", company.getLegalName());
      Element line = appendElement(xml, root, sac, "sac:SummaryDocumentsLine");
      appendTextElement(xml, line, CBC, "cbc:LineID", "1");
      appendTextElement(xml, line, CBC, "cbc:DocumentTypeCode", document.getDocumentType());
      appendTextElement(
              xml, line, CBC, "cbc:ID", document.getSeries() + "-" + document.getNumber());
      Element customer = appendElement(xml, line, CAC, "cac:AccountingCustomerParty");
      appendTextElement(
              xml,
              customer,
              CBC,
              "cbc:CustomerAssignedAccountID",
              document.getCustomer().getDocumentNumber());
      appendTextElement(
              xml, customer, CBC, "cbc:AdditionalAccountID", document.getCustomer().getDocumentType());
      Optional.ofNullable(document.getNote())
              .ifPresent(
                      note -> {
                        Element billing = appendElement(xml, line, CAC, "cac:BillingReference");
                        Element original = appendElement(xml, billing, CAC, "cac:InvoiceDocumentReference");
                        appendTextElement(
                                xml,
                                original,
                                CBC,
                                "cbc:ID",
                                note.getOriginalDocument().getSeries()
                                        + "-"
                                        + note.getOriginalDocument().getNumber());
                        appendTextElement(
                                xml,
                                original,
                                CBC,
                                "cbc:DocumentTypeCode",
                                note.getOriginalDocument().getDocumentType());
                      });
      Element status = appendElement(xml, line, CAC, "cac:Status");
      appendTextElement(xml, status, CBC, "cbc:ConditionCode", condition);
      // RC 1.1 informa los importes en la moneda del comprobante (regla 2071).
      // Convertir por separado las bases y el impuesto introducía diferencias de redondeo.
      String currency = document.getCurrency();
      summaryMoney(xml, line, sac, "sac:TotalAmount", document.getTotals().getTotalAmount(), currency);
      List<String> bases =
              List.of(
                      document.getTotals().getTaxableAmount(),
                      document.getTotals().getExemptAmount(),
                      document.getTotals().getUnaffectedAmount());
      for (int index = 0; index < bases.size(); index++) {
        if (new BigDecimal(bases.get(index)).signum() == 0) continue;
        Element payment = appendElement(xml, line, sac, "sac:BillingPayment");
        summaryMoney(xml, payment, CBC, "cbc:PaidAmount", bases.get(index), currency);
        appendTextElement(xml, payment, CBC, "cbc:InstructionID", "0" + (index + 1));
      }
      Element tax = appendElement(xml, line, CAC, "cac:TaxTotal");
      summaryMoney(xml, tax, CBC, "cbc:TaxAmount", document.getTotals().getIgvAmount(), currency);
      Element subtotal = appendElement(xml, tax, CAC, "cac:TaxSubtotal");
      summaryMoney(xml, subtotal, CBC, "cbc:TaxAmount", document.getTotals().getIgvAmount(), currency);
      Element category = appendElement(xml, subtotal, CAC, "cac:TaxCategory");
      appendTextElement(xml, category, CBC, "cbc:Percent", documentTaxRate(document).toPlainString());
      Element scheme = appendElement(xml, category, CAC, "cac:TaxScheme");
      appendTextElement(xml, scheme, CBC, "cbc:ID", "1000");
      appendTextElement(xml, scheme, CBC, "cbc:Name", "IGV");
      appendTextElement(xml, scheme, CBC, "cbc:TaxTypeCode", "VAT");
      if (document.getTotals().getIcbperAmount() != null) {
        Element bagTotal = appendElement(xml, line, CAC, "cac:TaxTotal");
        summaryMoney(xml, bagTotal, CBC, "cbc:TaxAmount", document.getTotals().getIcbperAmount(), currency);
        Element bagSubtotal = appendElement(xml, bagTotal, CAC, "cac:TaxSubtotal");
        summaryMoney(xml, bagSubtotal, CBC, "cbc:TaxAmount", document.getTotals().getIcbperAmount(), currency);
        appendBagScheme(xml, bagSubtotal);
      }
      byte[] signed = signDocument(xml, content, signing);
      schemaValidator.validate(signed, "RC");
      return signed;
    } catch (GeneralSecurityException
             | ParserConfigurationException
             | XMLSignatureException
             | MarshalException
             | TransformerException exception) {
      throw new ResponseStatusException(
              UNPROCESSABLE_CONTENT, "No se pudo generar y firmar el resumen RC.");
    }
  }
}
