<!-- GET /user/{username}: finds a user by user name -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:path="http://www.armatiek.nl/ns/xproc/openapi/parameter/path"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result" serialization="map{'indent': true(), 'escape-solidus': false()}"/>
  <p:option name="path:username" as="xs:string" required="true"/>
  <p:option name="req:headers" as="map(*)" select="map{}"/>

  <ps:get collection="users" key="{$path:username}"/>
  <p:variable name="found" select="collection()" collection="true"/>
  <p:choose>
    <p:when test="empty($found)">
      <ps:message status="404" message="User not found"/>
    </p:when>
    <p:otherwise>
      <ps:respond type="user" accept="{$req:headers?accept}"/>
    </p:otherwise>
  </p:choose>
</p:declare-step>
