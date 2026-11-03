package nl.armatiek.xproc.rest.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import nl.armatiek.xproc.XProcProcessor;
import nl.armatiek.xproc.rest.XProcProperties;
import nl.armatiek.xproc.rest.api.ProcessorApi;
import nl.armatiek.xproc.rest.model.ProcessorInfo;

@RestController
public class ProcessorController implements ProcessorApi {

  private final XProcProcessor processor;
  private final XProcProperties props;

  public ProcessorController(XProcProcessor processor, XProcProperties props) {
    this.processor = processor;
    this.props = props;
  }

  @Override
  public ResponseEntity<ProcessorInfo> getProcessorInfo() {
    var saxon = processor.getSaxonProcessor();
    return ResponseEntity.ok(new ProcessorInfo(processor.getProductName(),
        processor.getProductVersion(), "3.1", saxon.getSaxonProductVersion(),
        saxon.getSaxonEdition())
        .fileAccess(props.allowFileAccess())
        .networkAccess(props.allowNetworkAccess())
        .osAccess(props.allowOsAccess()));
  }
}
