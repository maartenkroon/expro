<!-- DELETE /pet/{petId}: deletes a pet (also when there is none, as in the reference implementation) -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:path="http://www.armatiek.nl/ns/xproc/openapi/parameter/path"
                xmlns:header="http://www.armatiek.nl/ns/xproc/openapi/parameter/header"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result"/>
  <p:option name="path:petId" as="xs:long" required="true"/>
  <!-- Declared in the description, but not used -->
  <p:option name="header:api_key" as="xs:string?"/>

  <ps:remove name="remove" collection="pets" key="{$path:petId}"/>
  <ps:message p:depends="remove" message="Pet deleted"/>
</p:declare-step>
