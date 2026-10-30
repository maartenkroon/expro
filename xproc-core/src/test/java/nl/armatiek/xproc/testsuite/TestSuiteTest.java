package nl.armatiek.xproc.testsuite;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the XProc 3 test suite when the system property {@code xproc.testsuite} points to its
 * {@code test-suite/tests} directory, for example:
 * {@code ./mvnw -pl xproc-core test -Dtest=TestSuiteTest -Dxproc.testsuite=/path/to/tests}.
 * The report is written to {@code target/testsuite-report.txt}.
 */
class TestSuiteTest {

  @Test
  @EnabledIfSystemProperty(named = "xproc.testsuite", matches = ".+")
  void runTestSuite() throws Exception {
    String filter = System.getProperty("xproc.testsuite.filter", "");
    TestSuiteRunner.main(new String[] {System.getProperty("xproc.testsuite"), filter,
        Path.of("target/testsuite-report.txt").toString()});
  }
}
