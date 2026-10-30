package nl.armatiek.xproc.rest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the XProc processor used by the REST service ({@code xproc.*}).
 *
 * @param allowFileAccess whether pipelines may read and write file: URIs
 * @param allowNetworkAccess whether pipelines may access the network
 * @param locale the value of the p:locale system property (null for the default)
 * @param maxPipelines the maximum number of stored pipelines
 */
@ConfigurationProperties("xproc")
public record XProcProperties(
    @DefaultValue("false") boolean allowFileAccess,
    @DefaultValue("false") boolean allowNetworkAccess,
    String locale,
    @DefaultValue("1000") int maxPipelines) {
}
