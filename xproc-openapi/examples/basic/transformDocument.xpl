<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                exclude-inline-prefixes="#all" version="3.1">
  <!-- The request body -->
  <p:input port="source" content-types="xml"/>
  <p:output port="result" content-types="xml"/>

  <!-- The compiled stylesheet is cached and reused by later requests -->
  <p:xslt>
    <p:with-input port="stylesheet" href="transform.xsl"/>
  </p:xslt>
</p:declare-step>
