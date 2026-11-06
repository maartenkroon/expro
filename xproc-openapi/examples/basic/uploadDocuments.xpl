<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:part="http://www.armatiek.nl/ns/xproc/openapi/multipart/part"
                xmlns:resp="http://www.armatiek.nl/ns/xproc/openapi/response"
                exclude-inline-prefixes="#all"
                version="3.1">
  <!-- The files of the multipart part named "document" -->
  <p:input port="document" sequence="true" content-types="any"/>
  <p:output port="result" serialization="map{'method': 'json'}"/>

  <!-- The non-file part named "comment" -->
  <p:option name="part:comment" as="xs:string?"/>

  <p:variable name="files" select="collection()" collection="true"/>
  <p:identity>
    <p:with-input select="map{
        'comment': $part:comment,
        'files': array{ for $f in $files return map{
          'content-type': string(p:document-property($f, 'content-type')),
          'base-uri': string(p:document-property($f, 'base-uri'))
        }}
      }">
      <p:inline content-type="application/json" expand-text="false">{}</p:inline>
    </p:with-input>
  </p:identity>
  <!-- 201 Created instead of 200 OK -->
  <p:set-properties properties="map{QName('http://www.armatiek.nl/ns/xproc/openapi/response',
    'status'): 201}"/>
</p:declare-step>
