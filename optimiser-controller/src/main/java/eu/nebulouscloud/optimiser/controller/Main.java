package eu.nebulouscloud.optimiser.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

/**
 * The main class of the optimizer controller.
 */
@Command(name = "nebulous-optimizer-controller",
         version = "0.1",       // TODO read this from Bundle-Version in the jar MANIFEST.MF
         mixinStandardHelpOptions = true,
         sortOptions = false,
         separator = " ",
         showAtFileInUsageHelp = true,
         description = "Receive app creation messages from the UI and start up the optimizer infrastructure.")
public class Main implements Callable<Integer> {

    @Option(names = {"-s", "--sal-url"},
            description = "The URL of the SAL server (including URL scheme http:// or https://). Can also be set via the @|bold SAL_URL|@ environment variable.",
            paramLabel = "SAL_URL",
            defaultValue = "${SAL_URL:-http://localhost:8880/}")
    private java.net.URI sal_uri;

    @Option(names = {"--sal-user"},
            description = "The user name for the SAL server. Can also be set via the @|bold SAL_USER|@ environment variable.",
            paramLabel = "SAL_USER",
            defaultValue = "${SAL_USER}")
    private String sal_user;

    @Option(names = {"--sal-password"},
            description = "The password for the SAL server. Can also be set via the @|bold SAL_PASSWORD|@ environment variable.",
            paramLabel = "SAL_PASSWORD",
            defaultValue = "${SAL_PASSWORD}")
    private String sal_password;

    @Option(names = {"--activemq-host"},
            description = "The hostname of the ActiveMQ server.  Can also be set via the @|bold ACTIVEMQ_HOST|@ environment variable.",
            paramLabel = "ACTIVEMQ_HOST",
            defaultValue = "${ACTIVEMQ_HOST:-localhost}")
    private String activemq_host;

    @Option(names = {"--activemq-port"},
            description = "The port of the ActiveMQ server.  Can also be set via the @|bold ACTIVEMQ_PORT|@ environment variable.",
            paramLabel = "ACTIVEMQ_PORT",
            defaultValue = "${ACTIVEMQ_PORT:-5672}")
    private int activemq_port;

    @Option(names = {"--activemq-user"},
            description = "The user name for the ActiveMQ server. Can also be set via the @|bold ACTIVEMQ_USER|@ environment variable.",
            paramLabel = "ACTIVEMQ_USER",
            defaultValue = "${ACTIVEMQ_USER}")
    private String activemq_user;

    @Option(names = {"--activemq-password"},
            description = "The password for the ActiveMQ server. Can also be set via the @|bold ACTIVEMQ_PASSWORD|@ environment variable.",
            paramLabel = "ACTIVEMQ_PASSWORD",
            defaultValue = "${ACTIVEMQ_PASSWORD}")
    private String activemq_password;

    @Option(names = {"--app-creation-message-file", "-f"},
            description = "The name of a file containing a JSON app creation message (used for testing purposes)")
    private Path json_app_creation_file;

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    /**
     * The main method of the main class.
     *
     * @return 0 if no error during execution, otherwise greater than 0
     */
    @Override
    public Integer call() {
        int success = 0;
        SalConnector sal_connector = null;
        ExnConnector activemq_connector = null;

        CountDownLatch exn_synchronizer = new CountDownLatch(1);
        if (sal_user != null && sal_password != null) {
            SalConnector connector = new SalConnector(sal_uri);
            connector.connect(sal_user, sal_password);
        }

        if (json_app_creation_file != null) {
            try {
                JSONObject msg = new JSONObject(Files.readString(json_app_creation_file, StandardCharsets.UTF_8));
                NebulousApp app = NebulousApp.newFromAppMessage(msg);
                app.printAMPL();
            } catch (IOException e) {
                log.error("Could not read an input file: ", e);
                success = 1;
            }
        }

        if (sal_uri != null && sal_user != null && sal_password != null) {
            sal_connector = new SalConnector(sal_uri);
            boolean connected = sal_connector.connect(sal_user, sal_password);
            if (!connected) {
                success = 2;
            }
        }

        if (activemq_user != null && activemq_password != null) {
            activemq_connector
                = new ExnConnector(activemq_host, activemq_port,
                    activemq_user, activemq_password,
                    new ConnectorHandler() {
                        public void onReady(AtomicReference<Context> context) {
                            log.info("Optimiser-controller connected to ActiveMQ");
                        }
                    }
            );
            activemq_connector.start(exn_synchronizer);
            try {
                exn_synchronizer.await();
            } catch (InterruptedException e) {
                // ignore
            }
        }

        return success;
    }

    /**
     * External entry point for the main class.  Parses command-line
     * parameters and invokes the `call` method.
     *
     * @param args the command-line parameters as passed by the user
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
