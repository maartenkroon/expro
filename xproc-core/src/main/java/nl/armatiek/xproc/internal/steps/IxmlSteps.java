package nl.armatiek.xproc.internal.steps;

import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.bottlecaps.markup.Blitz;
import de.bottlecaps.markup.BlitzException;
import de.bottlecaps.markup.blitz.Parser;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmNode;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.XProcDocument;
import nl.armatiek.xproc.XProcException;

/**
 * The step of the XProc 3.1 Invisible XML specification. p:invisible-xml (also available under
 * its former name p:ixml) parses non-XML input with an Invisible XML grammar using Markup Blitz.
 * Grammars may be given in the text or the XML form; without a grammar the Invisible XML grammar
 * itself is used. Generated parsers are cached by grammar.
 */
final class IxmlSteps {

  private static final int CACHE_SIZE = 32;

  private static final Map<String, Parser> PARSERS = Collections.synchronizedMap(
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Parser> eldest) {
          return size() > CACHE_SIZE;
        }
      });

  private IxmlSteps() {
  }

  static void register(StepRegistry r) {
    r.register("invisible-xml", () -> IxmlSteps::invisibleXml);
    r.register("ixml", () -> IxmlSteps::invisibleXml);
  }

  private static void invisibleXml(StepContext ctx) {
    List<XProcDocument> grammars = ctx.inputs("grammar");
    if (grammars.size() > 1) {
      throw XProcException.xc(211, "More than one document appears on the grammar port");
    }
    Parser parser = parser(ctx, grammars.isEmpty() ? null : grammars.get(0));
    boolean failOnError = ctx.option("fail-on-error").size() == 0
        || Boolean.parseBoolean(ctx.option("fail-on-error").itemAt(0).getStringValue());
    String input = sourceText(ctx, ctx.input("source"));
    String xml;
    try {
      xml = failOnError ? parser.parse(input, Blitz.Option.FAIL_ON_ERROR) : parser.parse(input);
    } catch (BlitzException e) {
      throw XProcException.xc(205, "The source cannot be parsed by the grammar: "
          + e.getMessage(), e);
    }
    XdmNode doc = ctx.pc().loader.parseXml(xml, null);
    ctx.output("result", XProcDocument.of(ctx.processor(), doc, MediaType.XML, (URI) null));
  }

  private static Parser parser(StepContext ctx, XProcDocument grammar) {
    boolean xml = grammar != null && grammar.getKind() == MediaType.Kind.XML;
    String text = grammar == null ? null : xml ? serialize(ctx, grammar.getNode())
        : StepSupport.text(grammar);
    String key = grammar == null ? "" : (xml ? "x" : "t") + text;
    Parser parser = PARSERS.get(key);
    if (parser == null) {
      try {
        parser = grammar == null ? Blitz.generate(Blitz.ixmlGrammar())
            : xml ? Blitz.generateFromXml(text) : Blitz.generate(text);
      } catch (BlitzException e) {
        throw XProcException.xc(212, "The grammar is not a valid Invisible XML grammar: "
            + e.getMessage(), e);
      }
      PARSERS.put(key, parser);
    }
    return parser;
  }

  private static String sourceText(StepContext ctx, XProcDocument source) {
    return switch (source.getKind()) {
      case TEXT -> StepSupport.text(source);
      case JSON -> MiscSteps.jsonText(ctx, source.getValue());
      default -> new String(source.getBinary(),
          source.getContentType().getCharset(StandardCharsets.UTF_8));
    };
  }

  private static String serialize(StepContext ctx, XdmNode node) {
    StringWriter sw = new StringWriter();
    Serializer ser = ctx.processor().newSerializer(sw);
    ser.setOutputProperty(Serializer.Property.METHOD, "xml");
    ser.setOutputProperty(Serializer.Property.OMIT_XML_DECLARATION, "yes");
    try {
      ser.serializeNode(node);
    } catch (SaxonApiException e) {
      throw XProcException.xc(212, "Cannot serialize the grammar: " + e.getMessage(), e);
    }
    return sw.toString();
  }
}
