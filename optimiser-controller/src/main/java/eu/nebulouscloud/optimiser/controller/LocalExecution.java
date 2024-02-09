package eu.nebulouscloud.optimiser.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import eu.nebulouscloud.exn.core.Publisher;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Slf4j
@Command(name = "local",
    aliases = {"l"},
    description = "Handle a single app creation message from the command line, printing its AMPL.  If an ActiveMQ connection is specified, additionally send a message to the solver.",
    mixinStandardHelpOptions = true)
public class LocalExecution implements Callable<Integer> {

    /** Reference to Main set up by PicoCLI.  This lets us ask for the SAL and
      * ActiveMQ connectors. */
    @ParentCommand
    private Main main;

    @Parameters(description = "The file containing a JSON app creation message")
    private Path app_creation_msg;

    @Override public Integer call() {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch exn_synchronizer = new CountDownLatch(1);
        ExnConnector connector = Main.getActiveMQConnector();
        if (connector != null) {
            connector.start(exn_synchronizer);
        }
        JsonNode msg;
	try {
	    msg = mapper.readTree(Files.readString(app_creation_msg, StandardCharsets.UTF_8));
	} catch (IOException e) {
            log.error("Could not read an input file: ", e);
            return 1;
        }
        NebulousApp app = NebulousApp.newFromAppMessage(msg, connector);
        if (connector != null) {
            log.debug("Sending AMPL to channel {}", connector.getAmplMessagePublisher());
            app.sendAMPL();
            app.deployUnmodifiedApplication();
        }
        System.out.println(AMPLGenerator.generateAMPL(app));
        // TODO: wait for solver reply here?
        return 0;
    }
}
