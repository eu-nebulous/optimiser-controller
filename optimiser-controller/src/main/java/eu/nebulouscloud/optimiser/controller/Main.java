package eu.nebulouscloud.optimiser.controller;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

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

    @Option(names = {"--log-dir"},
            description = "Directory where to log incoming and outgoing messages as files. Can also be set via the @|bold LOGDIR|@ variable.",
            paramLabel = "LOGDIR",
            defaultValue = "${LOGDIR}")
    @Getter
    private static Path logDirectory;

    @Option(names = {"--verbose", "-v"},
            description = "Turn on more verbose logging output. Can be given multiple times. When not given, print only warnings and error messages. With @|underline -v|@, print status messages. With @|underline -vvv|@, print everything.",
        scope = ScopeType.INHERIT)
    private boolean[] verbosity;

    /**
     * The ActiveMQ connector.
     *
     * @return the ActiveMQ connector wrapper, or null if running offline.
     */
    @Getter
    private static ExnConnector activeMQConnector = null;

    /**
     * PicoCLI execution strategy that uses common initialization.
     */
    private int executionStrategy(ParseResult parseResult) {
        init();
        return new CommandLine.RunLast().execute(parseResult);
    }

    /**
     * Initialization code shared between main and subcommands.  Note that
     * here we connect to SAL if possible, but (for now) do not start the EXN
     * ActiveMQ middleware. Each main method needs to call
     * `activeMQConnector.start`.
     */
    private void init() {
        log.debug("Beginning common startup of optimiser-controller");
        // Set log level.  java.util.logging wants to be configured with a
        // configuration file, convince it otherwise.
        java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
        Level level;
        if (verbosity == null) level = Level.SEVERE;
        else
            switch (verbosity.length) {
            case 0: level = Level.SEVERE; break; // can't happen
            case 1: level = Level.INFO; break;   // Skip warning, since I (rudi) want INFO with just `-v`
            case 2: level = Level.FINE; break;
            default: level = Level.ALL; break;
            }
        rootLogger.setLevel(level);
        for (java.util.logging.Handler handler : rootLogger.getHandlers()) {
            handler.setLevel(rootLogger.getLevel());
        }
        // Set up directory for file logs (dumps of contents of incoming or
        // outgoing messages).
        if (logDirectory != null) {
            if (!Files.exists(logDirectory)) {
                try {
                    Files.createDirectories(logDirectory);
                } catch (IOException e) {
                    log.warn("Could not create log directory {}. Continuing without file logging.");
                    logDirectory = null;
                }
            } else if (!Files.isDirectory(logDirectory) || !Files.isWritable(logDirectory)) {
                log.warn("Trying to use a file as log directory, or directory not writable: {}. Continuing without file logging.", logDirectory);
                logDirectory = null;
            } else {
                log.info("Logging all messages to directory {}", logDirectory);
            }
        }
        // Start connection to ActiveMQ if possible.
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
                  });
        } else {
            log.debug("ActiveMQ login info not set, only operating locally.");
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
            log.debug("Starting connection to ActiveMQ");
            activeMQConnector.start(exn_synchronizer);
        } else {
            log.error("ActiveMQ connector not initialized so we're unresponsive. Will keep running to keep CI/CD happy but don't expect anything more from me.");
        }
        // Note that we try to synchronize, even if we didn't connect to
        // ActiveMQ.  This is so that the container can be deployed.  (If the
        // container terminates, the build registers as unsuccessful.)
        log.info("Optimiser-controller Initialization complete, waiting for messages");
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

    /**
     * Log a file into the given log directory.  Does nothing if {@link
     * Main#logDirectory} is not set.
     *
     * @param name The filename.  Note that the file that is written will have
     *  a longer name including a timestamp, so this argument does not need to
     *  be unique.
     * @param contents The content of the file to be written.  Will be
     *  converted to String via `toString`.
     */
    public static void logFile(String name, Object contents) {
        if (Main.logDirectory == null) return;
        String prefix = LocalDateTime.now().toString();
        Path path = logDirectory.resolve(prefix + "--" + name);
        try (FileWriter out = new FileWriter(path.toFile())) {
            out.write(contents.toString());
            log.trace("Wrote log file {}", path);
        } catch (IOException e) {
            log.warn("Error while trying to create data file in log directory", e);
        }
    }
}
