<!-- POST /store/order: places an order (or replaces the order with its id) -->
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

  <ps:read-body type="order"/>
  <p:variable name="order" select="collection()/order" collection="true"/>
  <p:choose>
    <p:when test="empty($order)">
      <ps:message status="400" message="No Order provided. Try again?"/>
    </p:when>
    <p:otherwise>
      <ps:assign-id collection="orders"/>
      <ps:put collection="orders" key="{/order/id}"/>
      <ps:respond type="order" accept="{$req:headers?accept}"/>
    </p:otherwise>
  </p:choose>
</p:declare-step>
