<!-- GET /pet/findByStatus: the pets with one of the (comma separated) statuses -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:query="http://www.armatiek.nl/ns/xproc/openapi/parameter/query"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result" serialization="map{'indent': true(), 'escape-solidus': false()}"/>
  <p:option name="query:status" as="xs:string" select="'available'"/>
  <p:option name="req:headers" as="map(*)" select="map{}"/>

  <p:variable name="statuses" select="tokenize($query:status, ',') ! normalize-space()"/>
  <ps:list collection="pets"/>
  <p:variable name="pets" select="/pets/pet[status = $statuses]"/>
  <p:identity>
    <p:with-input>
      <p:inline><pets>{$pets}</pets></p:inline>
    </p:with-input>
  </p:identity>
  <ps:respond type="pet" accept="{$req:headers?accept}"/>
</p:declare-step>
