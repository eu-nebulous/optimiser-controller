package eu.nebulous.optimiser.controller;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
            description = "The URL of the SAL server (including URL scheme http:// or https://).",
            paramLabel = "SAL_URL",
            defaultValue = "${SAL_URL:-http://158.37.63.90:8880/}")
    private java.net.URI sal_uri;

    @Option(names = {"-u", "--sal-user"},
            description = "The user name for the SAL server.",
            paramLabel = "SAL_USER",
            defaultValue = "${SAL_USER}")
    private String sal_user;

    @Option(names = {"-p", "--sal-password"},
            description = "The password for the SAL server.",
            paramLabel = "SAL_PASSWORD",
            defaultValue = "${SAL_PASSWORD}")
    private String sal_password;

    @Option(names = {"--kubevela-file"},
            description = "The name of a deployable KubeVela yaml file (used for testing purposes)")
    private Path kubevela_file;

    @Option(names = {"--kubevela-parameters"},
            description = "The name of a parameter file referencing the deployable model (used for testing purposes)")
    private Path kubevela_parameters;

    private static final Logger log = LogManager.getLogger(Main.class.getName());

    /**
     * The main method of the main class.
     *
     * @return 0 if no error during execution, otherwise greater than 0
     */
    @Override
    public Integer call() {
        int success = 0;

        if (sal_user != null && sal_password != null) {
            SalConnector connector = new SalConnector(sal_uri);
            connector.connect(sal_user, sal_password);
        }

        if (kubevela_file != null && kubevela_parameters!= null) {
            try {
                NebulousApp app
                    = AppParser.parseAppCreationMessage(Files.readString(kubevela_file, StandardCharsets.UTF_8),
                                                        Files.readString(kubevela_parameters, StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.error("Could not read an input file: ", e);
                success = 1;
            }
        }

        if (sal_uri != null && sal_user != null && sal_password != null) {
            SalConnector sal_connector = new SalConnector(sal_uri);
            boolean connected = sal_connector.connect(sal_user, sal_password);
            if (!connected) {
                success = 2;
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
