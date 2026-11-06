<!-- POST /pet/{petId}: sets the name and status of a pet -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:path="http://www.armatiek.nl/ns/xproc/openapi/parameter/path"
                xmlns:query="http://www.armatiek.nl/ns/xproc/openapi/parameter/query"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result" serialization="map{'indent': true(), 'escape-solidus': false()}"/>
  <p:option name="path:petId" as="xs:long" required="true"/>
  <p:option name="query:name" as="xs:string?"/>
  <p:option name="query:status" as="xs:string?"/>
  <p:option name="req:headers" as="map(*)" select="map{}"/>

  <p:choose>
    <p:when test="empty($query:name)">
      <ps:message status="400" message="No Name provided. Try again?"/>
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
          <!-- As in the reference implementation, a missing status removes the status -->
          <p:identity>
            <p:with-input>
              <p:inline><pet>{$pet/id}<name>{$query:name}</name>{$pet/(category, photoUrls,
                tags)}<status>{$query:status}</status></pet></p:inline>
            </p:with-input>
          </p:identity>
          <p:delete match="/pet/status[. = '']"/>
          <ps:put collection="pets" key="{$path:petId}"/>
          <ps:respond type="pet" accept="{$req:headers?accept}"/>
        </p:otherwise>
      </p:choose>
    </p:otherwise>
  </p:choose>
</p:declare-step>
