package eu.nebulouscloud.optimiser.controller;

import com.amihaiemil.eoyaml.Yaml;

import java.io.IOException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AppParser {

    private static final Logger log = LoggerFactory.getLogger(AppParser.class);

    /**
     * Location of the kubevela yaml file in the app creation message.  Should
     * point to a string.
     */
    private static final JSONPointer kubevela_path
        = JSONPointer.builder().append("kubevela").append("original").build();

    /**
     * Location of the modifiable locations of the kubevela file in the app
     * creation message.  Should point to an array of objects.
     */
    private static final JSONPointer variables_path
        = JSONPointer.builder().append("kubevela").append("variables").build();

    /**
     * Parse an incoming app creation message in json format.
     *
     * @param json_message the app creation message
     * @return a {@code NebulousApp} instance, or {@code null} if there was an
     *  error parsing the app creation message
     */
    public static NebulousApp parseAppCreationMessage(JSONObject json_message) {
        String kubevela_string = (String)kubevela_path.queryFrom(json_message);
        JSONArray parameters = (JSONArray)variables_path.queryFrom(json_message);
        try {
            return new NebulousApp(Yaml.createYamlInput(kubevela_string).readYamlMapping(),
                                   parameters);
        } catch (IOException e) {
            log.error("Could not read app creation message: ", e);
            return null;
        }
    }

}
