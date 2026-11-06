package nl.armatiek.xproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for p:xsl-formatter (Apache FOP). */
class PagedMediaStepsTest extends TestSupport {

  private static final String FO = "xmlns:fo='http://www.w3.org/1999/XSL/Format'";

  @TempDir
  Path dir;

  private static String fo(String attributes, String content) {
    return "<fo:root " + FO + " " + attributes + "><fo:layout-master-set>"
        + "<fo:simple-page-master master-name='A4' page-height='297mm' page-width='210mm'>"
        + "<fo:region-body margin='20mm'/></fo:simple-page-master></fo:layout-master-set>"
        + "<fo:page-sequence master-reference='A4'><fo:flow flow-name='xsl-region-body'>"
        + content + "</fo:flow></fo:page-sequence></fo:root>";
  }

  private static String pipeline(String options, String source) {
    return "<p:declare-step " + P + " xmlns:xs='http://www.w3.org/2001/XMLSchema'>"
        + "<p:output port='result'/><p:xsl-formatter " + options + ">"
        + "<p:with-input>" + source + "</p:with-input></p:xsl-formatter></p:declare-step>";
  }

  private static String hello() {
    return fo("", "<fo:block>Hello, world</fo:block>");
  }

  private XProcDocument format(XProcProcessor processor, String options, String source) {
    XProcPipeline p = processor.compile(pipeline(options, source), base(), Map.of());
    List<XProcDocument> docs = p.run(Map.of(), Map.of()).getPrimaryDocuments();
    assertEquals(1, docs.size());
    return docs.get(0);
  }

  private XProcDocument format(String options, String source) {
    return format(PROCESSOR, options, source);
  }

  private static String ascii(byte[] bytes, int length) {
    return new String(bytes, 0, Math.min(length, bytes.length), StandardCharsets.ISO_8859_1);
  }

  @Test
  void pdfByDefault() {
    XProcDocument pdf = format("", hello());
    assertEquals("application/pdf", pdf.getContentType().toString());
    assertTrue(ascii(pdf.getBinary(), 5).equals("%PDF-"));
    assertTrue(pdf.getBaseURI() == null, "The result has no base URI");
  }

  @Test
  void otherOutputFormats() {
    XProcDocument png = format("content-type='image/png'", hello());
    assertEquals("image/png", png.getContentType().toString());
    assertEquals("\u0089PNG", ascii(png.getBinary(), 4));
    XProcDocument text = format("content-type='text/plain'", hello());
    assertEquals(MediaType.Kind.TEXT, text.getKind());
    assertTrue(text.getNode().getStringValue().contains("Hello, world"));
    XProcDocument ps = format("content-type='application/postscript'", hello());
    assertEquals("%!PS", ascii(ps.getBinary(), 4));
  }

  @Test
  void parameters() {
    XProcDocument pdf = format("parameters=\"map{'version': '1.7', 'title': 'Greeting', "
        + "'creation-date': xs:dateTime('2026-11-06T12:00:00Z')}\"", hello());
    String content = ascii(pdf.getBinary(), Integer.MAX_VALUE);
    assertEquals("%PDF-1.7", content.substring(0, 8));
    assertTrue(content.contains("Greeting"), "title in the document information");
    assertTrue(content.contains("D:20261106"), "creation date");
    assertError("XC0167", pipeline("parameters=\"map{'target-resolution': 'high'}\"", hello()));
    // A renderer option: PDF/A needs embedded fonts, which the base 14 fonts are not
    assertError("XC0167", pipeline("parameters=\"map{'pdf-a-mode': 'PDF/A-2b'}\"", hello()));
  }

  @Test
  void contentTypeErrors() {
    assertError("XC0204", pipeline("content-type='text/html'", hello()));
    assertError("XC0204", pipeline("content-type='application/X-fop-print'", hello()));
    assertError("XD0079", pipeline("content-type='pdf'", hello()));
  }

  @Test
  void documentThatCannotBeFormatted() {
    assertError("XC0167", pipeline("", "<fo:root " + FO + "><fo:block/></fo:root>"));
  }

  @Test
  void relativeImageUrisAgainstTheBaseUri() throws IOException {
    Path images = Files.createDirectories(dir.resolve("images"));
    ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png",
        images.resolve("square.png").toFile());
    String source = fo("xml:base='" + dir.toUri() + "'",
        "<fo:block xml:base='images/'><fo:external-graphic src=\"url('square.png')\"/>"
        + "</fo:block>");
    List<String> messages = Collections.synchronizedList(new ArrayList<>());
    XProcProcessor processor = new XProcProcessor(new XProcConfiguration()
        .setMessageListener((level, step, message) -> messages.add(level + " " + message)));
    String tree = ascii(format(processor, "content-type='application/X-fop-areatree'", source)
        .getBinary(), Integer.MAX_VALUE);
    assertTrue(tree.contains("url=\"" + images.resolve("square.png").toUri().toString()
        .replace("file:///", "file:/") + "\""), tree);
    assertTrue(messages.isEmpty(), messages.toString());

    // Without file access, FOP cannot read the image
    XProcProcessor safe = new XProcProcessor(new XProcConfiguration().setAllowFileAccess(false)
        .setMessageListener((level, step, message) -> messages.add(level + " " + message)));
    format(safe, "", source);
    assertTrue(messages.stream().anyMatch(m -> m.startsWith("WARNING")
        && m.contains("not allowed")), messages.toString());
    assertTrue(messages.stream().anyMatch(m -> m.startsWith("ERROR")
        && m.contains("Image not found")), messages.toString());
  }

  @Test
  void fopConfiguration() throws IOException {
    Path conf = dir.resolve("fop.xconf");
    Files.writeString(conf, "<fop version='1.0'><renderers><renderer mime='application/pdf'>"
        + "<version>1.6</version></renderer></renderers></fop>");
    XProcProcessor processor = new XProcProcessor(new XProcConfiguration()
        .setMessageListener(MessageListener.SILENT).setFopConfiguration(conf.toUri()));
    assertEquals("%PDF-1.6", ascii(format(processor, "", hello()).getBinary(), 8));

    // The configuration is read again after clearCaches()
    Files.writeString(conf, "<fop version='1.0'><renderers><renderer mime='application/pdf'>"
        + "<version>1.5</version></renderer></renderers></fop>");
    assertEquals("%PDF-1.6", ascii(format(processor, "", hello()).getBinary(), 8));
    processor.clearCaches();
    assertEquals("%PDF-1.5", ascii(format(processor, "", hello()).getBinary(), 8));

    Files.writeString(conf, "<fop");
    processor.clearCaches();
    XProcException e = assertThrows(XProcException.class, () -> format(processor, "", hello()));
    assertEquals("XC0167", e.getCode().getLocalName());
    assertFalse(e.getMessage().isEmpty());
  }

  @Test
  void relativeConfigurationUriIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> new XProcConfiguration().setFopConfiguration(java.net.URI.create("fop.xconf")));
  }
}
