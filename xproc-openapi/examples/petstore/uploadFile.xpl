<!-- POST /pet/{petId}/uploadImage: stores an image and adds its location to the photo URLs of the pet -->
<p:declare-step name="upload" xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:path="http://www.armatiek.nl/ns/xproc/openapi/parameter/path"
                xmlns:query="http://www.armatiek.nl/ns/xproc/openapi/parameter/query"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <!-- The image (application/octet-stream) -->
  <p:input port="source" sequence="true" content-types="any"/>
  <p:output port="result" serialization="map{'indent': true(), 'escape-solidus': false()}"/>
  <p:option name="path:petId" as="xs:long" required="true"/>
  <!-- Declared in the description, but not used (as in the reference implementation) -->
  <p:option name="query:additionalMetadata" as="xs:string?"/>
  <p:option name="req:headers" as="map(*)" select="map{}"/>

  <p:variable name="image" select="collection()" collection="true"/>
  <p:choose>
    <p:when test="empty($image)">
      <ps:message status="400" message="No file uploaded"/>
    </p:when>
    <p:otherwise>
      <ps:get collection="pets" key="{$path:petId}"/>
      <p:variable name="found" select="collection()" collection="true"/>
      <p:choose>
        <p:when test="empty($found)">
          <ps:message status="404" message="Pet not found"/>
        </p:when>
        <p:otherwise>
          <p:variable name="pet" select="/pet"/>
          <p:variable name="href" select="resolve-uri('data/images/' || $path:petId || '-'
              || format-dateTime(current-dateTime(), '[Y][M01][D01][H01][m01][s01][f001]'),
              static-base-uri())"/>
          <p:store name="store" href="{$href}">
            <p:with-input pipe="source@upload"/>
          </p:store>
          <p:identity depends="store">
            <p:with-input>
              <p:inline><pet>{$pet/(id, name, category)}<photoUrls>{$pet/photoUrls/photoUrl
                }<photoUrl>{$href}</photoUrl></photoUrls>{$pet/(tags, status)}</pet></p:inline>
            </p:with-input>
          </p:identity>
          <ps:put collection="pets" key="{$path:petId}"/>
          <ps:respond type="pet" accept="{$req:headers?accept}"/>
        </p:otherwise>
      </p:choose>
    </p:otherwise>
  </p:choose>
</p:declare-step>
