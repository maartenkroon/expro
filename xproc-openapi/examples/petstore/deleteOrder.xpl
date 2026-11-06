<!-- DELETE /store/order/{orderId}: deletes an order (an empty response, also when there is none) -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:path="http://www.armatiek.nl/ns/xproc/openapi/parameter/path"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result"/>
  <p:option name="path:orderId" as="xs:long" required="true"/>

  <ps:remove name="remove" collection="orders" key="{$path:orderId}"/>
  <ps:message p:depends="remove"/>
</p:declare-step>
