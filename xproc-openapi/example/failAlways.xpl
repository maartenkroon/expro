<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:ex="http://example.com/errors"
                exclude-inline-prefixes="#all"
                version="3.1">
  <!-- The xml serialization method makes errors application/problem+xml -->
  <p:output port="result" serialization="map{'method': 'xml'}"/>

  <p:error code="ex:always">
    <p:with-input>
      <message>This operation always fails</message>
    </p:with-input>
  </p:error>
</p:declare-step>
