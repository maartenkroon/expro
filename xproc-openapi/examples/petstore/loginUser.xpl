<!-- GET /user/login: "logs in" any user, as the reference implementation does -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:query="http://www.armatiek.nl/ns/xproc/openapi/parameter/query"
                xmlns:resp="http://www.armatiek.nl/ns/xproc/openapi/response"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result"/>
  <!-- Declared in the description, but not checked -->
  <p:option name="query:username" as="xs:string?"/>
  <p:option name="query:password" as="xs:string?"/>

  <p:variable name="session" select="
      xs:integer(random-number-generator(current-dateTime())?number * 1e18)"/>
  <!-- The format of java.util.Date.toString(), in UTC -->
  <p:variable name="expires" select="format-dateTime(adjust-dateTime-to-timezone(
      current-dateTime() + xs:dayTimeDuration('PT1H'), xs:dayTimeDuration('PT0S')),
      '[FNn,3-3] [MNn,3-3] [D01] [H01]:[m01]:[s01] UTC [Y0001]')"/>
  <ps:message message="Logged in user session: {$session}"/>
  <p:set-properties properties="map{xs:QName('resp:headers'):
      map{'X-Rate-Limit': '5000', 'X-Expires-After': $expires}}"/>
</p:declare-step>
