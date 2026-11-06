<!-- GET /pet/findByTags: the pets with one of the tags (repeated or comma separated) -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:query="http://www.armatiek.nl/ns/xproc/openapi/parameter/query"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result" serialization="map{'indent': true(), 'escape-solidus': false()}"/>
  <p:option name="query:tags" as="xs:string*"/>
  <p:option name="req:headers" as="map(*)" select="map{}"/>

  <p:variable name="tags" select="$query:tags ! tokenize(., ',') ! normalize-space()[. ne '']"/>
  <p:choose>
    <p:when test="empty($tags)">
      <ps:message status="400" message="No tags provided. Try again?"/>
    </p:when>
    <p:otherwise>
      <ps:list collection="pets"/>
      <p:variable name="pets" select="/pets/pet[tags/tag/name = $tags]"/>
      <p:identity>
        <p:with-input>
          <p:inline><pets>{$pets}</pets></p:inline>
        </p:with-input>
      </p:identity>
      <ps:respond type="pet" accept="{$req:headers?accept}"/>
    </p:otherwise>
  </p:choose>
</p:declare-step>
