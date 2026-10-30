package nl.armatiek.xproc;

/**
 * Describes an input or output port of a pipeline.
 *
 * @param name the port name
 * @param primary true for the primary port
 * @param sequence true if the port accepts/produces a sequence of documents
 * @param contentTypes the content-types attribute value (null means any)
 * @param hasDefault true for input ports with a default connection
 */
public record PortSignature(String name, boolean primary, boolean sequence, String contentTypes,
    boolean hasDefault) {
}
