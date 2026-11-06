<!-- PUT /user/{username}: replaces a user (the user name may change) -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:path="http://www.armatiek.nl/ns/xproc/openapi/parameter/path"
                xmlns:req="http://www.armatiek.nl/ns/xproc/openapi/request"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <!-- The request body: JSON, XML or form data -->
  <p:input port="source" sequence="true" content-types="any"/>
  <p:output port="result" serialization="map{'indent': true(), 'escape-solidus': false()}"/>
  <p:option name="path:username" as="xs:string" required="true"/>
  <p:option name="req:headers" as="map(*)" select="map{}"/>

  <ps:read-body name="body" type="user"/>
  <p:variable name="user" select="collection()/user" collection="true"/>
  <p:choose>
    <p:when test="empty($user)">
      <ps:message status="400" message="No User provided. Try again?"/>
    </p:when>
    <p:otherwise>
      <ps:get collection="users" key="{$path:username}"/>
      <p:variable name="existing" select="collection()" collection="true"/>
      <p:choose>
        <p:when test="empty($existing)">
          <ps:message status="404" message="User not found"/>
        </p:when>
        <p:otherwise>
          <ps:remove name="remove" collection="users" key="{$path:username}"/>
          <!-- A user without a user name keeps the one in the path -->
          <p:insert depends="remove" match="/user[not(username[. ne ''])]"
                    position="first-child">
            <p:with-input pipe="result@body"/>
            <p:with-input port="insertion">
              <p:inline><username>{$path:username}</username></p:inline>
            </p:with-input>
          </p:insert>
          <p:delete match="/user/username[. eq '']"/>
          <ps:put collection="users" key="{/user/username}"/>
          <ps:respond type="user" accept="{$req:headers?accept}"/>
        </p:otherwise>
      </p:choose>
    </p:otherwise>
  </p:choose>
</p:declare-step>
