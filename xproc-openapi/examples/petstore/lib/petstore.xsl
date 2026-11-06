<?xml version="1.0" encoding="UTF-8"?>
<!--
  Converts the entities of the pet store (pet, order, user) between their representations.
  The pipelines store entities as XML, in the shape the OpenAPI description gives for
  application/xml (wrapped photoUrls and tags). Request bodies may be JSON, XML or form data;
  responses are JSON or XML.

  Named templates, called by the steps of petstore.xpl with the stylesheet parameters below:
    ps:to-xml     the stored XML of a request body ($value: a JSON map or array, an XML
                  document or the map of p:www-form-urldecode with $form true)
    ps:represent  the response for stored XML ($value: an entity or a list of them) in the
                  format $format (json or xml)
-->
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:map="http://www.w3.org/2005/xpath-functions/map"
                xmlns:array="http://www.w3.org/2005/xpath-functions/array"
                xmlns:ps="http://www.armatiek.nl/ns/xproc/examples/petstore"
                exclude-result-prefixes="#all">

  <!-- Results are maps and arrays (JSON) or elements (XML): deliver them as they are -->
  <xsl:output build-tree="no"/>

  <!-- The parameters (p:xslt passes them as stylesheet parameters) -->
  <xsl:param name="value" as="item()*"/>
  <xsl:param name="type" as="xs:string" select="''"/>
  <xsl:param name="form" as="xs:boolean" select="false()"/>
  <xsl:param name="format" as="xs:string" select="'json'"/>

  <!-- The properties of the entities (components/schemas of the description), in order -->
  <xsl:variable name="schemas" as="element(schema)*">
    <schema name="pet">
      <field name="id" type="integer"/>
      <field name="name" type="string"/>
      <field name="category" type="category"/>
      <field name="photoUrls" type="string" item="photoUrl"/>
      <field name="tags" type="tag" item="tag"/>
      <field name="status" type="string"/>
    </schema>
    <schema name="category">
      <field name="id" type="integer"/>
      <field name="name" type="string"/>
    </schema>
    <schema name="tag">
      <field name="id" type="integer"/>
      <field name="name" type="string"/>
    </schema>
    <schema name="order">
      <field name="id" type="integer"/>
      <field name="petId" type="integer"/>
      <field name="quantity" type="integer"/>
      <field name="shipDate" type="string"/>
      <field name="status" type="string"/>
      <field name="complete" type="boolean"/>
    </schema>
    <schema name="user">
      <field name="id" type="integer"/>
      <field name="username" type="string"/>
      <field name="firstName" type="string"/>
      <field name="lastName" type="string"/>
      <field name="email" type="string"/>
      <field name="password" type="string"/>
      <field name="phone" type="string"/>
      <field name="userStatus" type="integer"/>
    </schema>
  </xsl:variable>

  <xsl:template name="ps:to-xml" as="element()?">
    <xsl:variable name="v" select="if ($value instance of document-node()) then $value/*
                                   else $value"/>
    <xsl:choose>
      <xsl:when test="$form">
        <xsl:sequence select="ps:element(ps:form-map($v, $type), $type)"/>
      </xsl:when>
      <xsl:when test="$v instance of array(*)">
        <xsl:element name="{$type}s">
          <xsl:sequence select="array:flatten($v) ! ps:element(., $type)"/>
        </xsl:element>
      </xsl:when>
      <xsl:when test="$v instance of map(*)">
        <xsl:sequence select="ps:element($v, $type)"/>
      </xsl:when>
      <!-- XML: through the map, which keeps the known properties in order -->
      <xsl:when test="$v instance of element() and local-name($v) = $type || 's'">
        <xsl:element name="{$type}s">
          <xsl:sequence select="$v/* ! ps:element(ps:map(., $type), $type)"/>
        </xsl:element>
      </xsl:when>
      <xsl:when test="$v instance of element()">
        <xsl:sequence select="ps:element(ps:map($v, $type), $type)"/>
      </xsl:when>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="ps:represent" as="item()?">
    <xsl:variable name="v" select="if ($value instance of document-node()) then $value/*
                                   else $value"/>
    <xsl:variable name="list" select="local-name($v) = $type || 's'"/>
    <xsl:choose>
      <xsl:when test="$format = 'xml'">
        <xsl:sequence select="if ($list) then $v else ps:element(ps:map($v, $type), $type)"/>
      </xsl:when>
      <xsl:when test="$list">
        <xsl:sequence select="array{ $v/* ! ps:map(., $type) }"/>
      </xsl:when>
      <xsl:otherwise>
        <xsl:sequence select="ps:map($v, $type)"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!-- A stored element as a JSON map: known properties only, typed by the schema -->
  <xsl:function name="ps:map" as="map(*)">
    <xsl:param name="e" as="element()"/>
    <xsl:param name="type" as="xs:string"/>
    <xsl:sequence select="map:merge(
        for $f in $schemas[@name = $type]/field,
            $c in $e/*[local-name() = $f/@name][1]
        return map{ string($f/@name):
          if ($f/@item) then array{ $c/*[local-name() = $f/@item] ! ps:atom(., $f/@type) }
          else ps:atom($c, $f/@type) })"/>
  </xsl:function>

  <xsl:function name="ps:atom" as="item()">
    <xsl:param name="e" as="element()"/>
    <xsl:param name="type" as="xs:string"/>
    <xsl:sequence select="
        if ($type = 'integer') then
          (if ($e castable as xs:integer) then xs:integer($e) else string($e))
        else if ($type = 'boolean') then
          (if ($e castable as xs:boolean) then xs:boolean($e) else string($e))
        else if ($type = 'string') then string($e)
        else ps:map($e, $type)"/>
  </xsl:function>

  <!-- A JSON map as an element: known properties only, in schema order -->
  <xsl:function name="ps:element" as="element()">
    <xsl:param name="m" as="map(*)"/>
    <xsl:param name="type" as="xs:string"/>
    <xsl:element name="{$type}">
      <xsl:for-each select="$schemas[@name = $type]/field[exists($m(@name))]">
        <xsl:variable name="f" select="."/>
        <xsl:element name="{@name}">
          <xsl:choose>
            <xsl:when test="@item">
              <xsl:for-each select="ps:members($m(@name))">
                <xsl:element name="{$f/@item}">
                  <xsl:sequence select="ps:content(., $f/@type)"/>
                </xsl:element>
              </xsl:for-each>
            </xsl:when>
            <xsl:otherwise>
              <xsl:sequence select="ps:content($m(@name), @type)"/>
            </xsl:otherwise>
          </xsl:choose>
        </xsl:element>
      </xsl:for-each>
    </xsl:element>
  </xsl:function>

  <xsl:function name="ps:content" as="node()*">
    <xsl:param name="v" as="item()*"/>
    <xsl:param name="type" as="xs:string"/>
    <xsl:choose>
      <xsl:when test="$type = ('integer', 'boolean', 'string')">
        <xsl:value-of select="$v"/>
      </xsl:when>
      <xsl:when test="$v instance of map(*)">
        <xsl:sequence select="ps:element($v, $type)/node()"/>
      </xsl:when>
    </xsl:choose>
  </xsl:function>

  <xsl:function name="ps:members" as="item()*">
    <xsl:param name="v" as="item()*"/>
    <xsl:sequence select="if ($v instance of array(*)) then array:flatten($v) else $v"/>
  </xsl:function>

  <!--
    Form data (name to values) as a JSON map. Objects and arrays, such as category and tags,
    may be given as JSON; repeated fields give arrays; a tag may also be given by its name.
  -->
  <xsl:function name="ps:form-map" as="map(*)">
    <xsl:param name="form" as="map(*)"/>
    <xsl:param name="type" as="xs:string"/>
    <xsl:sequence select="map:merge(
        for $f in $schemas[@name = $type]/field,
            $values in array{ $form(string($f/@name)) }[array:size(.) gt 0]
        return map{ string($f/@name):
          let $parsed := array:flatten($values) ! (
            if (matches(., '^\s*[\[{]')) then parse-json(.) else .)
          return
            if ($f/@item) then array{ $parsed ! ps:members(.) ! (
              if ($f/@type = 'tag' and . instance of xs:string) then map{ 'name': . } else .) }
            else $parsed[1] })"/>
  </xsl:function>
</xsl:stylesheet>
