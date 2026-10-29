package pe.invoicing.app.service.document.impl;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;
import pe.invoicing.app.service.document.models.DocumentData;
import pe.invoicing.app.service.document.models.DocumentItem;
import pe.invoicing.app.service.document.models.Installment;
import pe.invoicing.app.service.document.models.LineAmounts;
import pe.invoicing.app.service.document.models.NoteDetails;
import pe.invoicing.app.service.document.models.Payment;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public class DocumentPdfRenderer {
  private final ReceiptPresentation presentation;

  public DocumentPdfRenderer() { this(new ReceiptPresentation("")); }

  @org.springframework.beans.factory.annotation.Autowired
  public DocumentPdfRenderer(ReceiptPresentation presentation) { this.presentation = presentation; }

  private static final Charset PDF_ENCODING = Charset.forName("windows-1252");

  public byte[] render(
          String documentNumber, DocumentData document, String format, Optional<byte[]> signedXml) {
    try (PDDocument pdf = new PDDocument();
         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      try (InvoiceLayout layout = new InvoiceLayout(pdf, format)) {
        layout.logo(document.getIssuerLogoBase64());
        layout.center(
                Objects.toString(document.getIssuerLegalName(), documentNumber),
                true,
                layout.ticket ? 10 : 18);
        layout.center("RUC " + documentNumber, false, layout.fontSize);
        layout.center(Objects.toString(document.getIssuerFiscalAddress(), ""), false, layout.fontSize);
        layout.rule();
        layout.center(
                switch (document.getDocumentType()) {
                  case "03" -> "BOLETA DE VENTA ELECTRÓNICA";
                  case "07" -> "NOTA DE CRÉDITO ELECTRÓNICA";
                  case "08" -> "NOTA DE DÉBITO ELECTRÓNICA";
                  default -> "FACTURA ELECTRÓNICA";
                },
                true,
                layout.ticket ? 9 : 14);
        layout.center(
                document.getSeries()
                        + "-"
                        + String.format("%08d", Optional.ofNullable(document.getNumber()).orElse(0L)),
                true,
                layout.ticket ? 10 : 13);
        if (signedXml.isEmpty()) layout.center("BORRADOR · SIN XML FIRMADO", true, 8);
        layout.rule();
        layout.paragraph("Fecha de emisión: " + document.getIssueDate(), false);
        layout.paragraph("Cliente: " + document.getCustomer().getName(), false);
        layout.paragraph("Documento: " + document.getCustomer().getDocumentNumber(), false);
        for (String address :
                Optional.ofNullable(document.getCustomer().getAddress()).stream().toList())
          layout.paragraph("Dirección: " + address, false);
        layout.paragraph("Moneda: " + document.getCurrency(), false);
        layout.rule();
        layout.itemHeader();
        for (DocumentItem item : document.getItems()) layout.item(item);
        layout.rule();
        layout.nonzeroAmount("Op. gravadas", document.getTotals().getTaxableAmount());
        layout.nonzeroAmount("Op. exoneradas", document.getTotals().getExemptAmount());
        layout.nonzeroAmount("Op. inafectas", document.getTotals().getUnaffectedAmount());
        layout.nonzeroAmount("Descuentos", document.getTotals().getDiscountAmount());
        if (document.getDetraction() != null) {
          layout.amount("Detracción PEN " + document.getDetraction().rate() + "%",
                  document.getDetraction().amountPen(document.getTotals().getTotalAmount(), document.getCurrency(), document.getExchangeRate()).toPlainString(), false);
          layout.paragraph("Cuenta BN: " + document.getDetraction().bankAccount(), false);
          layout.paragraph("Operación sujeta al Sistema de Pago de Obligaciones Tributarias con el Gobierno Central", false);
        }
        layout.amount("IGV", document.getTotals().getIgvAmount(), false);
        if (document.getTotals().getIcbperAmount() != null) layout.amount("ICBPER", document.getTotals().getIcbperAmount(), false);
        layout.rule();
        layout.amount(
                "TOTAL " + document.getCurrency(), document.getTotals().getTotalAmount(), true);
        layout.rule();
        for (Payment payment : Optional.ofNullable(document.getPayment()).stream().toList()) {
          layout.paragraph(
                  "Forma de pago: " + ("CREDIT".equals(payment.getType()) ? "Crédito" : "Contado"),
                  false);
          {
            for (Installment installment :
                    Optional.ofNullable(payment.getInstallments()).orElseGet(List::of))
              layout.paragraph(
                      "Cuota "
                              + installment.getDueDate()
                              + ": "
                              + document.getCurrency()
                              + " "
                              + installment.getAmount(),
                      false);
          }
        }
        for (NoteDetails note : Optional.ofNullable(document.getNote()).stream().toList()) {
          layout.paragraph("Motivo: " + note.getReason(), false);
          layout.paragraph(
                  "Modifica: "
                          + note.getOriginalDocument().getSeries()
                          + "-"
                          + note.getOriginalDocument().getNumber(),
                  false);
        }
        for (String observation : Optional.ofNullable(document.getObservations()).stream().toList())
          layout.paragraph(observation, false);
        String consultation = presentation.documentUrl(document.getPublicationKey());
        if (signedXml.isPresent()) {
          String digest = signatureDigest(signedXml.orElseThrow());
          layout.qrPair(
                  String.join(
                          "|",
                          documentNumber,
                          document.getDocumentType(),
                          document.getSeries(),
                          Objects.toString(document.getNumber(), ""),
                          money(document.getTotals().getIgvAmount()),
                          money(document.getTotals().getTotalAmount()),
                          document.getIssueDate().toString(),
                          document.getCustomer().getDocumentType(),
                          document.getCustomer().getDocumentNumber(),
                          digest), consultation);

        }
        layout.center("Representación impresa del comprobante electrónico", false, 6.5f);
        if (!consultation.isEmpty()) {
          layout.center("Consulte y descargue su comprobante:", false, 6.5f);
          layout.link(consultation);
        }
        layout.center("Emitido con Fábrica Abstracta S.A.C.S.", false, 6.5f);
        // No imprimir detalles operativos en el ejemplar normal; tampoco ocultar un rechazo o anulación.
        if (!java.util.Set.of("ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS", "SUBMITTED").contains(document.getStatus()))
          layout.center(switch (document.getStatus()) {
            case "REJECTED" -> "COMPROBANTE RECHAZADO";
            case "CANCELLED" -> "COMPROBANTE ANULADO";
            case "HELD", "DRAFT" -> "BORRADOR - NO EMITIDO";
            default -> "EMISIÓN NO CONFIRMADA";
          }, true, 8);

      }
      pdf.save(output);
      return output.toByteArray();
    } catch (IOException | WriterException exception) {
      throw new IllegalStateException("No se pudo generar el PDF del comprobante.", exception);
    }
  }

  private static String price(String amount) { var value = new BigDecimal(amount).stripTrailingZeros(); return value.setScale(Math.max(2, value.scale())).toPlainString(); }

  private static String money(String amount) { return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).toPlainString(); }

  private String signatureDigest(byte[] xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      return factory
              .newDocumentBuilder()
              .parse(new ByteArrayInputStream(xml))
              .getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "DigestValue")
              .item(0)
              .getTextContent();
    } catch (Exception exception) {
      throw new IllegalStateException(
              "El XML firmado no contiene un resumen de firma válido.", exception);
    }
  }

  private static final class InvoiceLayout implements AutoCloseable {
    private final PDDocument pdf;
    private final PDFont regular;
    private final PDFont bold;
    private final boolean ticket;
    private final float width;
    private final float margin;
    private final float fontSize;
    private final float lineHeight;
    private final float height;
    private PDPageContentStream canvas;
    private PDPage page;
    private float cursor;

    private InvoiceLayout(PDDocument pdf, String format) throws IOException {
      this.pdf = pdf;
      ticket = !"A4".equals(format);
      width =
              ticket
                      ? Float.parseFloat(format.substring("TICKET_".length())) * 72 / 25.4f
                      : PDRectangle.A4.getWidth();
      height = ticket ? 3000 : PDRectangle.A4.getHeight();
      margin = ticket ? 9 : 40;
      fontSize = ticket ? 7.5f : 10;
      lineHeight = ticket ? 10 : 15;
      try (var normal = DocumentPdfRenderer.class.getResourceAsStream("/fonts/DejaVuSans.ttf");
           var strong = DocumentPdfRenderer.class.getResourceAsStream("/fonts/DejaVuSans-Bold.ttf")) {
        regular = PDType0Font.load(pdf, java.util.Objects.requireNonNull(normal));
        bold = PDType0Font.load(pdf, java.util.Objects.requireNonNull(strong));
      }
      newPage();
    }

    private void newPage() throws IOException {
      if (pdf.getNumberOfPages() > 0) finishPage();
      page = new PDPage(new PDRectangle(width, height));
      pdf.addPage(page);
      canvas = new PDPageContentStream(pdf, page);
      cursor = height - margin - 15;
    }

    private void requireSpace(float points) throws IOException {
      if (cursor - points < margin + 20) newPage();
    }

    private List<String> wrap(String text, float maxWidth, PDFont font, float size)
            throws IOException {
      List<String> lines = new ArrayList<>();
      StringBuilder line = new StringBuilder();
      for (String word :
              new String(text.replaceAll("[\\p{Cntrl}]+", " ").getBytes(PDF_ENCODING), PDF_ENCODING)
                      .split(" ")) {
        if (!line.isEmpty() && font.getStringWidth(line + " " + word) / 1000 * size > maxWidth) {
          lines.add(line.toString());
          line.setLength(0);
        }
        if (!line.isEmpty()) line.append(' ');
        for (int codePoint : word.codePoints().toArray()) {
          String character = new String(Character.toChars(codePoint));
          try {
            font.encode(character);
          } catch (IllegalArgumentException exception) {
            character = "?";
          }
          if (font.getStringWidth(line + character) / 1000 * size > maxWidth) {
            lines.add(line.toString());
            line.setLength(0);
          }
          line.append(character);
        }
      }
      if (!line.isEmpty()) lines.add(line.toString());
      return lines;
    }

    private void text(String value, float x, float y, boolean strong, float size)
            throws IOException {
      canvas.beginText();
      canvas.setFont(strong ? bold : regular, size);
      canvas.newLineAtOffset(x, y);
      canvas.showText(value);
      canvas.endText();
    }

    private void paragraph(String value, boolean strong) throws IOException {
      for (String line : wrap(value, width - 2 * margin, strong ? bold : regular, fontSize)) {
        requireSpace(lineHeight);
        text(line, margin, cursor, strong, fontSize);
        cursor -= lineHeight;
      }
    }

    private void logo(String base64) throws IOException {
      byte[] bytes = pe.invoicing.app.service.company.models.BusinessLogo.validate(base64);
      if (bytes == null) return;
      var image = org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject.createFromByteArray(pdf, bytes, "logo");
      float scale = Math.min((width - 2 * margin) * 0.75f / image.getWidth(), (ticket ? 70f : 90f) / image.getHeight());
      float w = image.getWidth() * scale, h = image.getHeight() * scale;
      requireSpace(h + 10);
      canvas.drawImage(image, (width - w) / 2, cursor - h, w, h);
      cursor -= h + 12;
    }

    private void center(String value, boolean strong, float size) throws IOException {
      PDFont font = strong ? bold : regular;
      for (String line : wrap(value, width - 2 * margin, font, size)) {
        requireSpace(size + 5);
        text(line, (width - font.getStringWidth(line) / 1000 * size) / 2, cursor, strong, size);
        cursor -= size + 5;
      }
    }

    private void rule() throws IOException {
      requireSpace(12);
      cursor -= 2;
      canvas.setStrokingColor(0.7f);
      canvas.setLineWidth(0.4f);
      canvas.moveTo(margin, cursor);
      canvas.lineTo(width - margin, cursor);
      canvas.stroke();
      cursor -= 12;
    }

    private void nonzeroAmount(String label, String value) throws IOException {
      if (new BigDecimal(value).signum() != 0) amount(label, value, false);
    }

    private void link(String url) throws IOException {
      float size = 6.3f;
      for (String line : wrap(url, width - 2 * margin, regular, size)) {
        requireSpace(10);
        float w = regular.getStringWidth(line) / 1000 * size;
        float x = (width - w) / 2;
        text(line, x, cursor, false, size);
        var annotation = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
        annotation.setRectangle(new PDRectangle(x, cursor - 2, w, 10));
        var border = new org.apache.pdfbox.cos.COSArray();
        border.add(org.apache.pdfbox.cos.COSInteger.ZERO); border.add(org.apache.pdfbox.cos.COSInteger.ZERO); border.add(org.apache.pdfbox.cos.COSInteger.ZERO);
        annotation.setBorder(border);
        var action = new org.apache.pdfbox.pdmodel.interactive.action.PDActionURI(); action.setURI(url);
        annotation.setAction(action); page.getAnnotations().add(annotation);
        cursor -= 10;
      }
    }

    private void right(String value, float right, float maxWidth, float size, boolean strong) throws IOException {
      PDFont font = strong ? bold : regular;
      float actual = Math.min(size, maxWidth * 1000 / Math.max(1, font.getStringWidth(value)));
      text(value, right - font.getStringWidth(value) / 1000 * actual, cursor, strong, actual);
    }

    private void amount(String label, String value, boolean strong) throws IOException {
      value = new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
      float size = strong ? fontSize + 1 : fontSize;
      PDFont font = strong ? bold : regular;
      float valueWidth = font.getStringWidth(value) / 1000 * size;
      if (font.getStringWidth(label) / 1000 * size + valueWidth + 10 > width - 2 * margin)
        paragraph(label, strong);
      else {
        requireSpace(lineHeight + 3);
        text(label, margin, cursor, strong, size);
      }
      text(value, width - margin - valueWidth, cursor, strong, size);
      cursor -= lineHeight + 3;
    }

    private void itemHeader() throws IOException {
      if (ticket) {
        requireSpace(22);
        if (width < 180) {
          text("DESCRIPCIÓN", margin, cursor, true, 7); cursor -= 10;
          text("CANT.", margin, cursor, true, 6.5f);
          right("P. UNIT.", width / 2 + 14, 45, 6.5f, true);
          right("TOTAL", width - margin, 45, 6.5f, true);
        } else {
          text("CANT.", margin, cursor, true, 6);
          text("DESCRIPCIÓN", margin + 22, cursor, true, 6);
          right("P. UNIT.", width - margin - 43, 35, 6, true);
          right("TOTAL", width - margin, 39, 6, true);
        }
        cursor -= 15;
      } else {
        requireSpace(24);
        text("DESCRIPCIÓN", margin, cursor, true, 9);
        text("CANT.", 323, cursor, true, 9);
        text("P. UNIT.", 385, cursor, true, 9);
        text("IMPORTE", 485, cursor, true, 9);
        cursor -= 20;
      }
    }

    private void item(DocumentItem item) throws IOException {
      LineAmounts amounts = LineAmounts.calculateLineAmounts(item);
      if (ticket) {
        float descriptionWidth = width < 180 ? width - 2 * margin : width - 2 * margin - 105;
        List<String> lines = wrap(item.getDescription(), descriptionWidth, regular, fontSize);
        float needed = (lines.size() + (width < 180 ? 1 : 0)) * lineHeight + 7;
        if (cursor - needed < margin + 20) { newPage(); itemHeader(); }
        if (width < 180) {
          for (String line : lines) { text(line, margin, cursor, false, fontSize); cursor -= lineHeight; }
          right(item.getQuantity(), margin + 24, 24, fontSize, false);
          right(price(item.getUnitPrice()), width / 2 + 14, 43, fontSize, false);
          right(money(amounts.total().toPlainString()), width - margin, 40, fontSize, false);
          cursor -= lineHeight;
        } else {
          right(item.getQuantity(), margin + 17, 17, fontSize, false);
          right(price(item.getUnitPrice()), width - margin - 43, 35, fontSize, false);
          right(money(amounts.total().toPlainString()), width - margin, 40, fontSize, false);
          for (String line : lines) { text(line, margin + 22, cursor, false, fontSize); cursor -= lineHeight; }
        }
        cursor -= 6;
      } else {
        List<String> lines = wrap(item.getDescription(), 265, regular, fontSize);
        if (cursor - lines.size() * lineHeight - 12 < margin + 20) {
          newPage();
          itemHeader();
        }
        text(item.getQuantity(), 323, cursor, false, 9);
        text(item.getUnitPrice(), 385, cursor, false, 9);
        String total = amounts.total().toPlainString();
        text(total, width - margin - regular.getStringWidth(total) / 1000 * 10, cursor, false, 10);
        for (String line : lines) {
          requireSpace(lineHeight);
          text(line, margin, cursor, false, fontSize);
          cursor -= lineHeight;
        }
        cursor -= 7;
      }
      if (amounts.discountTotal().signum() > 0)
        paragraph("Descuento: " + amounts.discountTotal().toPlainString(), false);
    }

    private void qrPair(String fiscal, String consultation) throws IOException, WriterException {
      if (!consultation.isEmpty() && width >= 180) {
        float size = Math.min(100, (width - margin * 2 - 12) / 2);
        requireSpace(size + 32);
        float left = (width - 2 * size - 12) / 2;
        text("QR fiscal", left, cursor, false, 6.5f);
        text("Consultar comprobante", left + size + 12, cursor, false, 6.5f);
        cursor -= 10;
        drawQr(fiscal, left, size); drawQr(consultation, left + size + 12, size);
        cursor -= size + 15;
      } else {
        center("QR fiscal", false, 6.5f);
        qr(fiscal);
        if (!consultation.isEmpty()) { center("Escanee para consultar", false, 6.5f); qr(consultation); }
      }
    }

    private void qr(String content) throws IOException, WriterException {
      float size = Math.min(105, width - margin * 2);
      requireSpace(size + 20);
      drawQr(content, (width - size) / 2, size);
      cursor -= size + 15;
    }

    private void drawQr(String content, float left, float size) throws IOException, WriterException {
      BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, java.util.Map.of(
              com.google.zxing.EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q,
              com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8", com.google.zxing.EncodeHintType.MARGIN, 4));
      // Evitar módulos inferiores al mínimo físico del QR fiscal (0,190 mm).
      if (size / matrix.getWidth() < 0.190f * 72 / 25.4f)
        throw new IllegalArgumentException("La URL es demasiado larga para un QR legible en este ancho.");
      float module = size / matrix.getWidth();
      for (int row = 0; row < matrix.getHeight(); row++)
        for (int column = 0; column < matrix.getWidth(); column++)
          if (matrix.get(column, row)) canvas.addRect(left + column * module, cursor - (row + 1) * module, module, module);
      canvas.fill();
    }

    private void finishPage() throws IOException {
      if (ticket) {
        float bottom = Math.max(0, cursor - margin);
        page.setMediaBox(new PDRectangle(0, bottom, width, height - bottom));
        page.setCropBox(page.getMediaBox());
      } else {
        text("Página " + pdf.getNumberOfPages(), margin, 20, false, 8);
      }
      canvas.close();
    }

    public void close() throws IOException {
      finishPage();
    }
  }
}
