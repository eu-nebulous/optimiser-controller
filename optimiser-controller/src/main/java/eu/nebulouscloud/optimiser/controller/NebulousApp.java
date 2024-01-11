package eu.nebulouscloud.optimiser.controller;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal representation of a NebulOus app.
 */
public class NebulousApp {
    private static final Logger log = LoggerFactory.getLogger(NebulousApp.class);

    /** Location of the kubevela yaml file in the app creation message (String) */
    private static final JsonPointer kubevela_path = JsonPointer.compile("/kubevela/original");

    /** Location of the variables (optimizable locations) of the kubevela file
     * in the app creation message. (Array of objects) */
    private static final JsonPointer variables_path = JsonPointer.compile("/kubevela/variables");

    /** Location of the UUID in the app creation message (String) */
    private static final JsonPointer uuid_path = JsonPointer.compile("/application/uuid");

    /** The YAML converter */
    // Note that instantiating this is apparently expensive, so we do it only once
    private static final ObjectMapper yaml_mapper = new ObjectMapper(new YAMLFactory());

    private String uuid;
    private JsonNode original_app_message;
    private ObjectNode original_kubevela;
    private ArrayNode kubevela_variables;
    /** Map from AMPL variable name to location in KubeVela. */
    private Map<String, JsonPointer> kubevela_variable_paths = new HashMap<>();

    /**
     * Creates a NebulousApp object.
     *
     * @param app_message The whole app creation message (JSON)
     * @param kubevela A parsed representation of the deployable KubeVela App model (YAML)
     * @param parameters A parameter mapping as a sequence of JSON objects (JSON)
     */
    // Note that example KubeVela and parameter files can be found at
    // optimiser-controller/src/test/resources/
    public NebulousApp(JsonNode app_message, ObjectNode kubevela, ArrayNode parameters) {
        this.original_app_message = app_message;
        this.original_kubevela = kubevela;
        this.kubevela_variables = parameters;
        for (JsonNode p : parameters) {
            kubevela_variable_paths.put(p.get("key").asText(),
                yqPathToJsonPointer(p.get("path").asText()));
        }
        this.uuid = app_message.at(uuid_path).textValue();
    }

    /**
     * Create a NebulousApp object given an app creation message parsed into JSON.
     *
     * @param app_message the app creation message, including valid KubeVela YAML et al
     * @return a NebulousApp object, or null if `app_message` could not be parsed
     */
    public static NebulousApp newFromAppMessage(JsonNode app_message) {
        try {
            String kubevela_string = app_message.at(kubevela_path).textValue();
            JsonNode parameters = app_message.at(variables_path);
            if (kubevela_string == null || !parameters.isArray()) {
                log.error("Could not find kubevela or parameters in app creation message.");
                return null;
            } else {
                return new NebulousApp(app_message,
                    (ObjectNode)yaml_mapper.readTree(kubevela_string),
                    (ArrayNode)parameters);
            }
        } catch (Exception e) {
            log.error("Could not read app creation message: ", e);
            return null;
        }
    }

    /**
     * The UUID of the app.  This is the UUID that identifies a specific
     * application's ActiveMQ messages.
     *
     * @return the UUID of the app
     */
    public String getUUID() {
        return uuid;
    }

    /**
     * Check that all parameters have a name, type and path, and that the
     * target path can be found in the original KubeVela file.
     *
     * @return true if all requirements hold, false otherwise
     */
    public boolean validatePaths() {
        for (final Object p : kubevela_variables) {
            ObjectNode param = (ObjectNode) p;
            String param_name = param.get("key").textValue();
            if (param_name == null || param_name.equals("")) return false;
            String param_type = param.get("type").textValue();
            if (param_type == null || param_type.equals("")) return false;
            // TODO: also validate types, upper and lower bounds, etc.
            String target_path = param.get("path").textValue();
            if (target_path == null || target_path.equals("")) return false;
            JsonNode target = findPathInKubevela(target_path);
            if (target == null) return false; // must exist
        }
        return true;
    }

    /**
     * Rewrite ".spec.components[3].properties.edge.cpu" (yq path as
     * delivered in the parameter file) into
     * "/spec/components/3/properties/edge/cpu" (JSON Pointer notation,
     * https://datatracker.ietf.org/doc/html/rfc6901)
     *
     * @param yq_path the path in yq notation.
     * @return the path as JsonPointer.
     */
    private static JsonPointer yqPathToJsonPointer(String yq_path) {
        String normalizedQuery = yq_path.replaceAll("\\[(\\d+)\\]", ".$1").replaceAll("\\.", "/");
        return JsonPointer.compile(normalizedQuery);
    }

    /**
     * Return the location of a path in the application's KubeVela model.
     *
     * @param path the path to the requested node, in yq notation (see <a
     *  href="https://mikefarah.gitbook.io/yq/">https://mikefarah.gitbook.io/yq/</a>)
     * @return the node identified by the given path, or null if the path
     * cannot be followed
     */
    private JsonNode findPathInKubevela(String path) {
        // rewrite ".spec.components[3].properties.edge.cpu" (yq path as
        // delivered in the parameter file) into
        // "/spec/components/3/properties/edge/cpu" (JSON Pointer notation,
        // https://datatracker.ietf.org/doc/html/rfc6901)
        JsonNode result = original_kubevela.at(yqPathToJsonPointer(path));
        return result.isMissingNode() ? null : result;
    }

    /**
     * Replace variables in the original KubeVela with values calculated by
     * the solver.  We look up the paths of the variables in the `parameters`
     * field.
     *
     * @param variable_values A JSON object with keys being variable names and
     *  their values the replacement value, e.g., `{ 'P1': 50, 'P2': 2.5 }`.
     * @return the modified KubeVela YAML, deserialized into a string, or
     *  null if no KubeVela could be generated.
     */
    public String rewriteKubevela(ObjectNode variable_values) {
        ObjectNode fresh_kubevela = original_kubevela.deepCopy();
        for (Map.Entry<String, JsonNode> entry : variable_values.properties()) {
            // look up the prepared path in the variable |-> location map
            JsonPointer path = kubevela_variable_paths.get(entry.getKey());
            JsonNode node = fresh_kubevela.at(path);
            if (node == null) {
                log.error("Location {} not found in KubeVela, cannot replace value", entry.getKey());
            } else if (!node.getNodeType().equals(entry.getValue().getNodeType())) {
                // Let's assume this is necessary
                log.error("Trying to replace value with a value of a different type");
            } else {
                // get the parent object and the property name; replace with
                // what we got
                ObjectNode parent = (ObjectNode)fresh_kubevela.at(path.head());
                String property = path.last().getMatchingProperty();
                parent.replace(property, entry.getValue());
            }
        }
        String result;
	try {
	    result = yaml_mapper.writeValueAsString(fresh_kubevela);
	} catch (JsonProcessingException e) {
            log.error("Could not generate KubeVela file: ", e);
            return null;
	}
        return result;
    }

    /**
     * Print AMPL code for the app, based on the parameter definition(s).
     */
    public void printAMPL() {
        for (final JsonNode p : kubevela_variables) {
            ObjectNode param = (ObjectNode) p;
            String param_name = param.get("key").textValue();
            String param_type = param.get("type").textValue();
            ObjectNode value = (ObjectNode)param.get("value");
            if (param_type.equals("float")) {
                System.out.format("var %s", param_name);
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    if (lower.isDouble()) {
                        System.out.format (" >= %s", lower.doubleValue());
                        separator = ", ";
                    }
                    if (upper.isDouble()) {
                        System.out.format("%s<= %s", separator, upper.doubleValue());
                    }
                }
                System.out.println(";");
            } else if (param_type.equals("int")) {
                System.out.format("var %s integer", param_name);
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    if (lower.isLong()) {
                        System.out.format (" >= %s", lower.longValue());
                        separator = ", ";
                    }
                    if (upper.isLong()) {
                        System.out.format("%s<= %s", separator, upper.longValue());
                    }
                }
                System.out.println(";");
            } else if (param_type.equals("string")) {
                System.out.println("# TODO not sure how to specify a string variable");
                System.out.format("var %s symbolic;%n");
            } else if (param_type.equals("array")) {
                System.out.format("# TODO generate entries for map '%s'%n", param_name);
            }
        }
    }

}
