package eu.nebulouscloud.optimiser.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "local",
    aliases = {"l"},
    description = "Handle a single app creation message from the command line, printing its AMPL.  If an ActiveMQ connection is specified, additionally send a message to the solver.",
    mixinStandardHelpOptions = true
)
public class LocalExecution implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(LocalExecution.class);

    /**
     * Reference to Main, to access activemq_connector, sal_connector etc.
     */
    @ParentCommand
    private Main main;

    @Parameters(description = "The file containing a JSON app creation message")
    private Path app_creation_msg;

    @Override public Integer call() {
        int success = 0;
        if (app_creation_msg != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode msg = mapper.readTree(Files.readString(app_creation_msg, StandardCharsets.UTF_8));
                NebulousApp app = NebulousApp.newFromAppMessage(msg, null);
                System.out.println(app.generateAMPL());
            } catch (IOException e) {
                log.error("Could not read an input file: ", e);
                success = 1;
            }
        }
        return success;
    }
}
