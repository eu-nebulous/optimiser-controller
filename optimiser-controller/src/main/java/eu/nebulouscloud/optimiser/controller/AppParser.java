package eu.nebulouscloud.optimiser.controller;

import com.amihaiemil.eoyaml.Yaml;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppParser {

    private static final Logger log = LoggerFactory.getLogger(AppParser.class);

    /**
     * Parse a KubeVela file and mapping file.
     *
     * @param kubevela a deployable KubeVela file
     * @param mappings parameter mappings for the KubeVela file
     * @return a {@code NebulousApp} instance, or {@code null} if there was an
     * error parsing the app creation message
     */
    public static NebulousApp parseAppCreationMessage(String kubevela, String mappings) {
        try {
            return new NebulousApp(Yaml.createYamlInput(kubevela).readYamlMapping(),
                                   Yaml.createYamlInput(mappings).readYamlMapping());
        } catch (IOException e) {
            log.error("Could not read app creation data: ", e);
            return null;
        }
    }

}
