package eu.nebulouscloud.optimiser.controller;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import eu.nebulouscloud.exn.core.Context;
import eu.nebulouscloud.exn.handlers.ConnectorHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.ScopeType;

import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

/**
 * The main class of the optimizer controller.
 */
@Slf4j
@Command(name = "nebulous-optimizer-controller",
    version = "0.1",       // TODO read this from Bundle-Version in the jar MANIFEST.MF
    mixinStandardHelpOptions = true,
    sortOptions = false,
    separator = " ",
    showAtFileInUsageHelp = true,
    description = "Receive app creation messages from the UI and start up the optimizer infrastructure.",
    subcommands = {
        LocalExecution.class
    }
)
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

    @Option(names = {"--verbose", "-v"},
        description = "Turn on more verbose logging output.",
        scope = ScopeType.INHERIT)
    public void setVerbose(boolean[] verbose) {
        // java.util.logging wants to be configured with a configuration file.
        // Convince it otherwise.
        java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
        rootLogger.setLevel(java.util.logging.Level.FINER);
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(rootLogger.getLevel());
        }
    }

    /**
     * The connector to the SAL library.
     *
     * @return the SAL connector, or null if running offline.
     */
    @Getter
    private SalConnector salConnector = null;
    /**
     * The ActiveMQ connector.
     *
     * @return the ActiveMQ connector wrapper, or null if running offline.
     */
    @Getter
    private ExnConnector activeMQConnector = null;

    /**
     * PicoCLI execution strategy that uses common initialization.
     */
    private int executionStrategy(ParseResult parseResult) {
        init();
        return new CommandLine.RunLast().execute(parseResult);
    }

    /**
     * Initialization code shared between main and subcommands.
     */
    private void init() {
        log.info("Beginning common startup of optimiser-controller");

        if (sal_uri != null && sal_user != null && sal_password != null) {
            salConnector = new SalConnector(sal_uri, sal_user, sal_password);
            if (!salConnector.isConnected()) {
                log.error("Connection to SAL unsuccessful");
            } else {
                log.info("Established connection to SAL");
                // FIXME: remove this once we have the exn connector
                NebulousApp.setSalConnector(salConnector);
            }
        } else {
            log.info("SAL login information not specified, skipping");
        }

        if (activemq_user != null && activemq_password != null) {
            log.info("Preparing ActiveMQ connection: host={} port={}",
                activemq_host, activemq_port);
            activeMQConnector
                = new ExnConnector(activemq_host, activemq_port,
                    activemq_user, activemq_password,
                    new ConnectorHandler() {
                        public void onReady(AtomicReference<Context> context) {
                            log.info("Optimiser-controller connected to ActiveMQ");
                        }
                    }
            );
        } else {
            log.info("ActiveMQ login info not set, only operating locally.");
        }
    }

    /**
     * The main method of the main class.
     *
     * @return 0 if no error during execution, otherwise greater than 0
     */
    @Override
    public Integer call() {
        CountDownLatch exn_synchronizer = new CountDownLatch(1);
        if (activeMQConnector != null) {
            log.info("Starting connection to ActiveMQ");
            activeMQConnector.start(exn_synchronizer);
        } else {
            log.error("ActiveMQ connector not initialized so we're unresponsive. Will keep running to keep CI/CD happy but don't expect anything more from me.");
        }
        // Note that we try to synchronize, even if we didn't connect to
        // ActiveMQ.  This is so that the container can be deployed.  (If the
        // container terminates, the build registers as unsuccessful.)
        try {
            exn_synchronizer.await();
        } catch (InterruptedException e) {
            // ignore
        }
        return 0;
    }

    /**
     * External entry point for the main class.  Parses command-line
     * parameters and invokes the `call` method.
     *
     * @param args the command-line parameters as passed by the user
     */
    public static void main(String[] args) {
        Main main = new Main();
        int exitCode = new CommandLine(main)
            .setExecutionStrategy(main::executionStrategy) // perform common initialization
            .execute(args);
        System.exit(exitCode);
    }
}
