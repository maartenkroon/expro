<!-- GET /user/logout: "logs out", as the reference implementation does -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result"/>

  <ps:message message="User logged out"/>
</p:declare-step>
