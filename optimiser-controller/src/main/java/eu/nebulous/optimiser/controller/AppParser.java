package eu.nebulous.optimiser.controller;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;

import java.io.File;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AppParser {

    private static final Logger log = LogManager.getLogger(AppParser.class.getName());

    /**
     * Parse a KubeVela file.  The file should be deployable as-is.
     *
     * @param kubevela_file the file to be parsed
     * @return true if `kubevela_file` could be parsed
     */
    public boolean parseKubevela(File kubevela_file) {
        try {
            YamlMapping m = Yaml.createYamlInput(kubevela_file).readYamlMapping();
        } catch (IOException e) {
            log.error("Could not parse " + kubevela_file + ": ", e);
            return false;
        }
        return true;
    }

    /**
     * Parse a parameterized KubeVela file.  Such a file is not directly
     * deployable, since it contains ranges for various parameters.
     *
     * @param kubevela_param_file the file to be parsed
     * @return true if `kubevela_param_file` could be parsed
     */
    public boolean parseParameterizedKubevela(File kubevela_param_file) {
        try {
            YamlMapping m = Yaml.createYamlInput(kubevela_param_file).readYamlMapping();
        } catch (IOException e) {
            log.error("Could not parse " + kubevela_param_file + ": ", e);
            return false;
        }
        return true;
    }

    /**
     * Parse a metric model.  This file contains the metric model for a
     * parameterized KubeVela file.
     */
    public boolean parseMetricModel(File metricmodel_file) {
        try {
            YamlMapping m = Yaml.createYamlInput(metricmodel_file).readYamlMapping();
        } catch (IOException e) {
            log.error("Could not parse " + metricmodel_file + ": ", e);
            return false;
        }
        return true;
    }
}
