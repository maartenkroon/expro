<!-- GET /store/inventory: the quantities of the orders by status, as in the reference implementation -->
<p:declare-step xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:map="http://www.w3.org/2005/xpath-functions/map"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-inline-prefixes="#all"
                version="3.1">
  <p:import href="lib/petstore.xpl"/>
  <p:output port="result" serialization="map{'indent': true(), 'escape-solidus': false()}"/>

  <ps:list collection="orders"/>
  <p:identity>
    <p:with-input select="map:merge(
        for $s in distinct-values(/orders/order/status)
        return map{ $s: sum(/orders/order[status = $s]/quantity[. castable as xs:integer]
          ! xs:integer(.)) })"/>
  </p:identity>
</p:declare-step>
