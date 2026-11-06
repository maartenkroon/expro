<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:query="http://www.armatiek.nl/ns/xproc/openapi/parameter/query"
                xmlns:cookie="http://www.armatiek.nl/ns/xproc/openapi/parameter/cookie"
                xmlns:map="http://www.w3.org/2005/xpath-functions/map"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:output port="result" serialization="map{'method': 'json'}"/>

  <p:option name="query:tags" as="xs:string*"/>
  <p:option name="cookie:session" as="xs:string?"/>
  <p:option name="req:method" as="xs:string"/>
  <p:option name="req:uri" as="xs:string"/>
  <p:option name="req:path" as="xs:string"/>
  <p:option name="req:path-template" as="xs:string"/>
  <p:option name="req:operation-id" as="xs:string"/>
  <p:option name="req:headers" as="map(xs:string, xs:string*)"/>
  <p:option name="req:query" as="map(xs:string, xs:string*)"/>

  <p:identity>
    <p:with-input select="map{
        'method': $req:method, 'uri': $req:uri, 'path': $req:path,
        'path-template': $req:path-template, 'operation-id': $req:operation-id,
        'tags': array{ $query:tags }, 'session': $cookie:session,
        'user-agent': $req:headers?user-agent,
        'query': map:merge(map:keys($req:query) ! map{.: array{$req:query(.)}})
      }">
      <p:inline content-type="application/json" expand-text="false">{}</p:inline>
    </p:with-input>
  </p:identity>
</p:declare-step>
