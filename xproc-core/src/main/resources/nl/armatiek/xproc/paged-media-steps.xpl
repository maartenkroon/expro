<p:library xmlns:e="http://www.w3.org/1999/XSL/Spec/ElementSyntax"
           xmlns:p="http://www.w3.org/ns/xproc"
           version="3.1">
   <!--
     The steps of XProc 3.1 Paged Media. p:css-formatter is left out on purpose: no CSS
     formatter is included, so the step is unknown (err:XS0044), as for a processor that does
     not support it.
   -->
   <p:declare-step type="p:xsl-formatter" xml:id="xsl-formatter">
      <p:input port="source" content-types="xml"/>
      <p:output port="result" content-types="any"/>
      <p:option name="parameters" as="map(xs:QName,item()*)?"/>
      <p:option name="content-type" as="xs:string?"/>
   </p:declare-step>
</p:library>
