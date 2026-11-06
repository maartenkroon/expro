<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:resp="http://www.armatiek.nl/ns/xproc/openapi/response"
                exclude-inline-prefixes="#all" version="3.1">
  <!-- The request body: an XSL-FO document -->
  <p:input port="source" content-types="xml"/>
  <p:output port="result" content-types="application/pdf"/>

  <!-- Apache FOP is configured by fop.xconf in this directory (xproc.openapi.fop-config) -->
  <p:xsl-formatter content-type="application/pdf"
                   parameters="map{'author': 'John Doe', 'title': 'Formatted by Expro'}"/>

  <!-- A download (Swagger UI then offers the file instead of showing it as text) -->
  <p:set-properties properties="map{xs:QName('resp:headers'):
      map{'Content-Disposition': 'attachment; filename=&quot;document.pdf&quot;'}}"/>
</p:declare-step>
