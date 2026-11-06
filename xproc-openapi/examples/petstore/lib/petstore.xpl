<!--
  The steps the pet store pipelines share: storage, request bodies and responses.

  The entities are stored as XML files, one per entity, in data/pets, data/orders and
  data/users (next to this lib directory). A collection is copied from seed/ the first time it
  is used, so the store starts with the data of the reference implementation. Delete the data
  directory to start over.
-->
<p:library xmlns:p="http://www.w3.org/ns/xproc"
           xmlns:c="http://www.w3.org/ns/xproc-step"
           xmlns:xs="http://www.w3.org/2001/XMLSchema"
           xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
           xmlns:resp="http://www.armatiek.nl/ns/xproc/openapi/response"
           exclude-inline-prefixes="#all"
           version="3.1">

  <p:option name="ps:data" static="true" select="resolve-uri('../data/', static-base-uri())"/>
  <p:option name="ps:seed" static="true" select="resolve-uri('../seed/', static-base-uri())"/>

  <p:declare-step type="ps:init">
    <p:documentation>Copies a collection from seed/ if it does not exist yet.</p:documentation>
    <p:option name="collection" as="xs:string" required="true"/>
    <p:try>
      <p:file-info href="{$ps:data}{$collection}/"/>
      <p:catch>
        <p:file-copy href="{$ps:seed}{$collection}" target="{$ps:data}"/>
      </p:catch>
    </p:try>
    <p:sink/>
  </p:declare-step>

  <p:declare-step type="ps:list">
    <p:documentation>All entities of a collection, in a wrapper named after it (pets, orders
      or users) and ordered by id.</p:documentation>
    <p:output port="result"/>
    <p:option name="collection" as="xs:string" required="true"/>
    <ps:init name="init" collection="{$collection}"/>
    <p:directory-list depends="init" path="{$ps:data}{$collection}/"
                      include-filter="\.xml$"/>
    <p:for-each>
      <p:with-input select="/c:directory/c:file"/>
      <p:load href="{$ps:data}{$collection}/{encode-for-uri(/c:file/@name)}"/>
    </p:for-each>
    <p:variable name="entities" select="collection()" collection="true"/>
    <p:identity>
      <p:with-input>
        <p:inline><list>{sort($entities/*, (), function($e) { number($e/id) })}</list></p:inline>
      </p:with-input>
    </p:identity>
    <p:rename match="/*" new-name="{$collection}"/>
  </p:declare-step>

  <p:declare-step type="ps:get">
    <p:documentation>The entity with a key (the id, or the user name of a user), or nothing.
    </p:documentation>
    <p:output port="result" sequence="true"/>
    <p:option name="collection" as="xs:string" required="true"/>
    <p:option name="key" as="xs:string" required="true"/>
    <ps:init name="init" collection="{$collection}"/>
    <p:try depends="init">
      <p:load href="{$ps:data}{$collection}/{encode-for-uri(encode-for-uri($key))}.xml"/>
      <p:catch>
        <p:identity>
          <p:with-input><p:empty/></p:with-input>
        </p:identity>
      </p:catch>
    </p:try>
  </p:declare-step>

  <p:declare-step type="ps:put" name="put">
    <p:documentation>Stores an entity under a key, replacing the one that has it.
    </p:documentation>
    <p:input port="source"/>
    <p:output port="result"/>
    <p:option name="collection" as="xs:string" required="true"/>
    <p:option name="key" as="xs:string" required="true"/>
    <ps:init name="init" collection="{$collection}"/>
    <p:store depends="init"
             href="{$ps:data}{$collection}/{encode-for-uri(encode-for-uri($key))}.xml"
             serialization="map{'indent': true()}">
      <p:with-input pipe="source@put"/>
    </p:store>
  </p:declare-step>

  <p:declare-step type="ps:remove">
    <p:documentation>Deletes the entity with a key, if there is one.</p:documentation>
    <p:option name="collection" as="xs:string" required="true"/>
    <p:option name="key" as="xs:string" required="true"/>
    <ps:init name="init" collection="{$collection}"/>
    <p:file-delete depends="init" fail-on-error="false"
                   href="{$ps:data}{$collection}/{encode-for-uri(encode-for-uri($key))}.xml"/>
    <p:sink/>
  </p:declare-step>

  <p:declare-step type="ps:read-body">
    <p:documentation>The request body as stored XML: a JSON object or array, an XML document
      or form data (application/x-www-form-urlencoded). No body gives nothing.
    </p:documentation>
    <p:input port="source" sequence="true" content-types="any"/>
    <p:output port="result" sequence="true"/>
    <p:option name="type" as="xs:string" required="true"/>
    <p:variable name="body" select="collection()" collection="true"/>
    <p:choose>
      <p:when test="empty($body)">
        <p:identity>
          <p:with-input><p:empty/></p:with-input>
        </p:identity>
      </p:when>
      <p:when test="starts-with(p:document-property($body[1], 'content-type'),
                    'application/x-www-form-urlencoded')">
        <p:www-form-urldecode value="{string($body[1])}"/>
        <p:variable name="form" select="."/>
        <ps:to-xml type="{$type}" form="true">
          <p:with-option name="value" select="$form"/>
        </ps:to-xml>
      </p:when>
      <p:otherwise>
        <ps:to-xml type="{$type}">
          <p:with-option name="value" select="$body[1]"/>
        </ps:to-xml>
      </p:otherwise>
    </p:choose>
  </p:declare-step>

  <p:declare-step type="ps:to-xml">
    <p:output port="result" sequence="true"/>
    <p:option name="value" as="item()" required="true"/>
    <p:option name="type" as="xs:string" required="true"/>
    <p:option name="form" as="xs:boolean" select="false()"/>
    <p:xslt template-name="ps:to-xml"
            parameters="map{'value': $value, 'type': $type, 'form': $form}">
      <p:with-input port="source"><p:empty/></p:with-input>
      <p:with-input port="stylesheet" href="petstore.xsl"/>
    </p:xslt>
  </p:declare-step>

  <p:declare-step type="ps:respond">
    <p:documentation>The response for stored XML (an entity or a list of them): JSON, or XML if
      the first media type the request accepts is application/xml, as in the reference
      implementation.</p:documentation>
    <p:input port="source"/>
    <p:output port="result"/>
    <p:option name="type" as="xs:string" required="true"/>
    <p:option name="accept" as="xs:string?"/>
    <p:variable name="entity" select="."/>
    <p:variable name="format" select="
        if (lower-case(normalize-space(substring-before(
          tokenize($accept, ',')[1] || ';', ';'))) = 'application/xml') then 'xml' else 'json'"/>
    <p:xslt template-name="ps:represent"
            parameters="map{'value': $entity, 'type': $type, 'format': $format}">
      <p:with-input port="source"><p:empty/></p:with-input>
      <p:with-input port="stylesheet" href="petstore.xsl"/>
    </p:xslt>
  </p:declare-step>

  <p:declare-step type="ps:message">
    <p:documentation>A text/plain response, such as "Pet not found" with status 404, as the
      reference implementation gives.</p:documentation>
    <p:output port="result"/>
    <p:option name="message" as="xs:string" select="''"/>
    <p:option name="status" as="xs:integer" select="200"/>
    <p:identity>
      <p:with-input>
        <p:inline content-type="text/plain">{$message}</p:inline>
      </p:with-input>
    </p:identity>
    <p:set-properties properties="map{xs:QName('resp:status'): $status}"/>
  </p:declare-step>

  <p:declare-step type="ps:assign-id" name="assign">
    <p:documentation>Gives an entity without an id one more than the highest id in its
      collection.</p:documentation>
    <p:input port="source"/>
    <p:output port="result"/>
    <p:option name="collection" as="xs:string" required="true"/>
    <p:choose>
      <p:when test="/*/id[. castable as xs:integer]">
        <p:identity/>
      </p:when>
      <p:otherwise>
        <ps:list collection="{$collection}"/>
        <p:variable name="next" select="
            (max(/*/*/id[. castable as xs:integer] ! xs:integer(.)), 0)[1] + 1"/>
        <p:delete match="/*/id">
          <p:with-input pipe="source@assign"/>
        </p:delete>
        <p:insert match="/*" position="first-child">
          <p:with-input port="insertion">
            <p:inline><id>{$next}</id></p:inline>
          </p:with-input>
        </p:insert>
      </p:otherwise>
    </p:choose>
  </p:declare-step>
</p:library>
