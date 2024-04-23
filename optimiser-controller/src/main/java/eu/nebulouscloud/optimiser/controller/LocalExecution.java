package eu.nebulouscloud.optimiser.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import org.slf4j.MDC;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Slf4j
@Command(name = "local",
    aliases = {"l"},
    description = "Start single app from the command line.",
    mixinStandardHelpOptions = true)
public class LocalExecution implements Callable<Integer> {

    /** Reference to Main set up by PicoCLI.  This lets us ask for the SAL and
      * ActiveMQ connectors. */
    @ParentCommand
    private Main main;

    @Parameters(description = "The file containing a JSON app creation message, as sent by the GUI")
    private Path appCreationMessage;

    @Option(names = {"--pe"},
        description = "The file containing a JSON performance indicator message, as sent by the utility evaluator.  If not supplied and --keepalive is given, wait for a message from the utility evaluator instead.  Note that the utility evaluator must somehow receive the same app creation message.")
    private Path perfIndicatorMessage;

    @Option(names = { "--deploy" },
        description = "Deploy application (default true).",
        defaultValue = "true", fallbackValue = "true",
        negatable = true)
    private boolean deploy;

    @Option(names = { "--keepalive"},
        description = "Stay alive and process messages from other components after initial deployment (default true).",
        defaultValue = "true", fallbackValue = "true",
        negatable = true)
    private boolean keepalive;

    @Override public Integer call() {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch exn_synchronizer = new CountDownLatch(1);
        ExnConnector connector = Main.getActiveMQConnector();
        if (connector != null) {
            connector.start(exn_synchronizer);
        }
        JsonNode app_msg = null;
        JsonNode perf_msg = null;
        try {
            app_msg = mapper.readTree(Files.readString(appCreationMessage, StandardCharsets.UTF_8));
            if (perfIndicatorMessage != null)
                perf_msg = mapper.readTree(Files.readString(perfIndicatorMessage, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Could not read an input file: {}",
                app_msg == null ? appCreationMessage : perfIndicatorMessage, e);
            return 1;
        }
        NebulousApp app = NebulousApp.newFromAppMessage(app_msg, connector);
        MDC.put("appId", app.getUUID());
        MDC.put("clusterName", app.getClusterName());
        if (perf_msg != null)
            app.setStateReady(perf_msg);
        if (connector != null) {
            if (deploy) {
                if (perf_msg != null) {
                    log.info("Deploying application", connector.getAmplMessagePublisher());
                    app.deployUnmodifiedApplication();
                } else {
                    log.warn("Performance indicators not supplied, cannot deploy");
                }
            } else {
                log.info("No deploy requested, printing AMPL and performance metric list");
                String ampl = AMPLGenerator.generateAMPL(app);
                System.out.println("--------------------");
                System.out.println("AMPL");
                System.out.println("--------------------");
                System.out.println(ampl);
                System.out.println("--------------------");
                System.out.println("Metrics");
                System.out.println("--------------------");
                AMPLGenerator.getMetricList(app).forEach(System.out::println);
            }
        }
        if (connector != null) {
            if (keepalive) {
                try {
                    exn_synchronizer.await();
                } catch (InterruptedException e) {
                    // ignore
                }
            } else {
                log.info("No keepalive requested, exiting");
            }
            try {
                connector.stop();
            } catch (Exception e) {
                // exn-connector-java throws spurious Groovy-internal error
                // when stopping; let's not crash in the end
            }
        }
        return 0;
    }
}
