<!-- POST /user: adds a user (or replaces the user with its user name) -->
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

  <ps:read-body type="user"/>
  <p:variable name="user" select="collection()/user" collection="true"/>
  <p:choose>
    <p:when test="empty($user)">
      <ps:message status="400" message="No User provided. Try again?"/>
    </p:when>
    <p:when test="empty($user/username[. ne ''])">
      <ps:message status="400" message="No username provided. Try again?"/>
    </p:when>
    <p:otherwise>
      <ps:put collection="users" key="{$user/username}"/>
      <ps:respond type="user" accept="{$req:headers?accept}"/>
    </p:otherwise>
  </p:choose>
</p:declare-step>
