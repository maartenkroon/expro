<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:path="http://www.armatiek.nl/ns/xproc/openapi/parameter/path"
                xmlns:query="http://www.armatiek.nl/ns/xproc/openapi/parameter/query"
                xmlns:header="http://www.armatiek.nl/ns/xproc/openapi/parameter/header"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:output port="result" serialization="map{'method': 'xml', 'indent': true()}"/>

  <p:option name="path:name" as="xs:string" required="true"/>
  <p:option name="query:lang" as="xs:string" select="'en'"/>
  <p:option name="query:repeat" as="xs:integer" select="1"/>
  <p:option name="header:X-Request-Id" as="xs:string?"/>
  <p:option name="req:method" as="xs:string"/>

  <p:identity>
    <p:with-input>
      <greeting lang="{$query:lang}" request-id="{$header:X-Request-Id}" method="{$req:method}"
        >{string-join((1 to $query:repeat) ! ((if ($query:lang = 'nl') then 'Hallo' else 'Hello')
          || ' ' || $path:name || '!'), ' ')}</greeting>
    </p:with-input>
  </p:identity>
</p:declare-step>
