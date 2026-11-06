<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="3.0">
  <xsl:mode on-no-match="shallow-copy"/>
  <xsl:template match="/*">
    <transformed elements="{count(//*)}">
      <xsl:next-match/>
    </transformed>
  </xsl:template>
</xsl:stylesheet>
