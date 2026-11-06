<!-- PUT /pet: replaces the pet with the id of the pet in the body -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <!-- The request body: JSON, XML or form data -->
  <p:input port="source" sequence="true" content-types="any"/>
  <p:output port="result" serialization="map{'indent': true(), 'escape-solidus': false()}"/>
  <p:option name="req:headers" as="map(*)" select="map{}"/>

  <ps:read-body name="body" type="pet"/>
  <p:variable name="pet" select="collection()/pet" collection="true"/>
  <p:choose>
    <p:when test="empty($pet)">
      <ps:message status="400" message="No Pet provided. Try again?"/>
    </p:when>
    <p:otherwise>
      <ps:get collection="pets" key="{$pet/id}"/>
      <p:variable name="existing" select="collection()" collection="true"/>
      <p:choose>
        <p:when test="empty($existing)">
          <ps:message status="404" message="Pet not found"/>
        </p:when>
        <p:otherwise>
          <ps:put collection="pets" key="{$pet/id}">
            <p:with-input pipe="result@body"/>
          </ps:put>
          <ps:respond type="pet" accept="{$req:headers?accept}"/>
        </p:otherwise>
      </p:choose>
    </p:otherwise>
  </p:choose>
</p:declare-step>
