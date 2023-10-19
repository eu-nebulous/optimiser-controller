package eu.nebulous.optimiser.controller;

import java.io.File;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import static picocli.CommandLine.Command;
import static picocli.CommandLine.Option;

/**
 * The main class of the optimizer controller.
 */
@Command(name = "nebulous-optimizer-controller",
         version = "0.1",       // TODO read this from Bundle-Version in the jar MANIFEST.MF
         mixinStandardHelpOptions = true,
         description = "Receive app creation messages from the UI and start up the optimizer infrastructure.")
public class Main implements Callable<Integer> {

    @Option(names = {"-k", "--kubevela-file"},
            description = "The name of a KubeVela yaml file to process (mostly for testing purposes)")
    private File kubevela_file;

    @Option(names = {"-p", "--kubevela-parameterized-file"},
            description = "The name of a parameterized KubeVela yaml file to process (mostly for testing purposes)")
    private File kubevela_parameterized_file;

    @Option(names = {"-m", "--resource-model-file"},
            description = "The name of a resource model to process (mostly for testing purposes)")
    private File resourcemodel_file;


    /**
     * The main method of the main class.
     *
     * @return 0 if no error during execution, otherwise greater than 0
     */
    @Override
    public Integer call() {
        int success = 0;
        AppParser p = new AppParser();
        if (kubevela_file != null) {
            success = p.parseKubevela(kubevela_file) ? success : 1;
        }
        if (kubevela_parameterized_file != null) {
            success = p.parseParameterizedKubevela(kubevela_parameterized_file) ? success : 2;
        }
        if (resourcemodel_file != null) {
            success = p.parseMetricModel(resourcemodel_file) ? success : 3;
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
