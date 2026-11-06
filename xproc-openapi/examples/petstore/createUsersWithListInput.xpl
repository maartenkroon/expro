<!-- POST /user/createWithList: adds the users of a JSON array -->
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

  <ps:read-body name="body" type="user"/>
  <p:variable name="users" select="collection()/users/user[username ne '']" collection="true"/>
  <p:choose>
    <p:when test="empty($users)">
      <ps:message status="400" message="No User provided. Try again?"/>
    </p:when>
    <p:otherwise>
      <p:for-each name="store">
        <p:with-input select="/users/user[username ne '']"/>
        <ps:put collection="users" key="{/user/username}"/>
      </p:for-each>
      <ps:respond p:depends="store" type="user" accept="{$req:headers?accept}">
        <p:with-input pipe="result@body"/>
      </ps:respond>
    </p:otherwise>
  </p:choose>
</p:declare-step>
