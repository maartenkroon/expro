package nl.armatiek.xproc.internal.steps;

import java.net.URI;
import java.util.List;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import net.sf.saxon.s9api.XdmNode;
import nl.armatiek.xproc.MediaType;
import nl.armatiek.xproc.XProcDocument;

/**
 * The steps of the XProc 3.1 Text Steps specification. p:markdown-to-html converts CommonMark,
 * with the GitHub Flavored Markdown table and strikethrough extensions, into an XHTML document
 * (html, head and body elements in the XHTML namespace).
 */
final class TextFormatSteps {

  private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(),
      StrikethroughExtension.create());
  private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
  private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS)
      .build();

  private TextFormatSteps() {
  }

  static void register(StepRegistry r) {
    r.register("markdown-to-html", () -> TextFormatSteps::markdownToHtml);
  }

  private static void markdownToHtml(StepContext ctx) {
    String markdown = StepSupport.text(ctx.input("source"));
    String body = RENDERER.render(PARSER.parse(markdown));
    // The HTML5 parser puts the result in the XHTML namespace and adds head and body elements
    XdmNode html = ctx.pc().loader.parseHtml("<!DOCTYPE html><html><head></head><body>" + body
        + "</body></html>", null);
    ctx.output("result", XProcDocument.of(ctx.processor(), html, MediaType.HTML, (URI) null));
  }
}
