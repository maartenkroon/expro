package nl.armatiek.xproc.internal.steps;

import java.io.IOException;

import javax.xml.XMLConstants;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.ValidatorHandler;

import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.thaiopensource.util.PropertyMap;
import com.thaiopensource.validate.AbstractSchema;
import com.thaiopensource.validate.AbstractSchemaReader;
import com.thaiopensource.validate.IncorrectSchemaException;
import com.thaiopensource.validate.Option;
import com.thaiopensource.validate.Schema;
import com.thaiopensource.validate.SchemaReader;
import com.thaiopensource.validate.SchemaReaderFactory;
import com.thaiopensource.validate.ValidateProperty;
import com.thaiopensource.validate.Validator;

/**
 * Lets Jing (and so NVDL) validate with W3C XML Schema through the JAXP implementation of the
 * JDK. Jing's own XML Schema support needs Apache Xerces. Registered as a service.
 */
public final class JaxpXsdSchemaReaderFactory implements SchemaReaderFactory {

  @Override
  public SchemaReader createSchemaReader(String namespaceUri) {
    return XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(namespaceUri) ? new Reader() : null;
  }

  @Override
  public Option getOption(String uri) {
    return null;
  }

  private static final class Reader extends AbstractSchemaReader {

    @Override
    public Schema createSchema(SAXSource source, PropertyMap properties)
        throws IOException, SAXException, IncorrectSchemaException {
      ErrorHandler handler = properties.get(ValidateProperty.ERROR_HANDLER);
      boolean[] failed = {false};
      SchemaFactory factory = SchemaFactory.newDefaultInstance();
      factory.setErrorHandler(new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) throws SAXException {
          if (handler != null) {
            handler.warning(e);
          }
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
          failed[0] = true;
          if (handler != null) {
            handler.error(e);
          }
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
          failed[0] = true;
          if (handler != null) {
            handler.fatalError(e);
          }
        }
      });
      EntityResolver resolver = properties.get(ValidateProperty.ENTITY_RESOLVER);
      if (resolver instanceof LSResourceResolver lsr) {
        factory.setResourceResolver(lsr);
      }
      javax.xml.validation.Schema schema;
      try {
        schema = factory.newSchema(source);
      } catch (SAXException e) {
        throw new IncorrectSchemaException();
      }
      if (failed[0]) {
        throw new IncorrectSchemaException();
      }
      return new AbstractSchema(properties) {
        @Override
        public Validator createValidator(PropertyMap props) {
          return new JaxpValidator(schema, props.get(ValidateProperty.ERROR_HANDLER));
        }
      };
    }

    @Override
    public Option getOption(String uri) {
      return null;
    }
  }

  private static final class JaxpValidator implements Validator {
    private final javax.xml.validation.Schema schema;
    private final ErrorHandler handler;
    private ValidatorHandler current;

    JaxpValidator(javax.xml.validation.Schema schema, ErrorHandler handler) {
      this.schema = schema;
      this.handler = handler;
      reset();
    }

    @Override
    public ContentHandler getContentHandler() {
      return current;
    }

    @Override
    public DTDHandler getDTDHandler() {
      return null;
    }

    @Override
    public void reset() {
      current = schema.newValidatorHandler();
      current.setErrorHandler(handler);
    }
  }
}
