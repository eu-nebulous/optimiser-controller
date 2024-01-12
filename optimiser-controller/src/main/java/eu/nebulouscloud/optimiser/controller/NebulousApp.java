package eu.nebulouscloud.optimiser.controller;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import eu.nebulouscloud.exn.core.Publisher;

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

    /** General-purpose object mapper */
    private static final ObjectMapper mapper = new ObjectMapper();

    private String uuid;
    private JsonNode original_app_message;
    private ObjectNode original_kubevela;
    private ArrayNode kubevela_variables;
    /** Map from AMPL variable name to location in KubeVela. */
    private Map<String, JsonPointer> kubevela_variable_paths = new HashMap<>();
    /** When an app gets deployed or redeployed, this is where we send the AMPL file */
    private Publisher ampl_message_channel;
    /** Have we ever been deployed?  I.e., when we rewrite KubeVela, are there
     * already nodes running for us? */
    private boolean deployed = false;

    /**
     * Creates a NebulousApp object.
     *
     * @param app_message The whole app creation message (JSON)
     * @param kubevela A parsed representation of the deployable KubeVela App model (YAML)
     * @param parameters A parameter mapping as a sequence of JSON objects (JSON)
     */
    // Note that example KubeVela and parameter files can be found at
    // optimiser-controller/src/test/resources/
    public NebulousApp(JsonNode app_message, ObjectNode kubevela, ArrayNode parameters, Publisher ampl_message_channel) {
        this.original_app_message = app_message;
        this.original_kubevela = kubevela;
        this.kubevela_variables = parameters;
        this.ampl_message_channel = ampl_message_channel;
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
     * @param ampl_message_channel conduit to broadcast the current AMPL file
     * @return a NebulousApp object, or null if `app_message` could not be parsed
     */
    public static NebulousApp newFromAppMessage(JsonNode app_message, Publisher ampl_message_channel) {
        try {
            String kubevela_string = app_message.at(kubevela_path).textValue();
            JsonNode parameters = app_message.at(variables_path);
            if (kubevela_string == null || !parameters.isArray()) {
                log.error("Could not find kubevela or parameters in app creation message.");
                return null;
            } else {
                return new NebulousApp(app_message,
                    (ObjectNode)yaml_mapper.readTree(kubevela_string),
                    (ArrayNode)parameters,
                    ampl_message_channel);
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
     * Set "deployed" status. Will typically be set to true once, and then
     * never to false again.
     *
     * @param deployed the new status.
     */
    public void setDeployed(boolean deployed) {
        this.deployed = deployed;
    }
    /**
     * Check if the app has been deployed, i.e., if there are already VMs
     * allocated from SAL for us.
     *
     * @return false if we never asked for nodes, true otherwise.
     */
    public boolean isDeployed() {
        return deployed;
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
     * Calculate AMPL file and send it off to the right channel.
     */
    public void sendAMPL() {
        String ampl = generateAMPL();
        ObjectNode msg = mapper.createObjectNode();
        msg.put(getUUID() + ".ampl", ampl);
        ampl_message_channel.send(mapper.convertValue(msg, Map.class), getUUID());
    }

    /**
     * Generate AMPL code for the app, based on the parameter definition(s).
     * Public for testability, not because we'll be calling it outside of its
     * class.
     */
    public String generateAMPL() {
        StringBuilder result = new StringBuilder();
        for (final JsonNode p : kubevela_variables) {
            ObjectNode param = (ObjectNode) p;
            String param_name = param.get("key").textValue();
            String param_type = param.get("type").textValue();
            ObjectNode value = (ObjectNode)param.get("value");
            if (param_type.equals("float")) {
                result.append(String.format("var %s", param_name));
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    if (lower.isDouble()) {
                        result.append(String.format(" >= %s", lower.doubleValue()));
                        separator = ", ";
                    }
                    if (upper.isDouble()) {
                        result.append(String.format("%s<= %s", separator, upper.doubleValue()));
                    }
                }
                result.append(";");
            } else if (param_type.equals("int")) {
                result.append(String.format("var %s integer", param_name));
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    if (lower.isLong()) {
                        result.append(String.format(" >= %s", lower.longValue()));
                        separator = ", ";
                    }
                    if (upper.isLong()) {
                        result.append(String.format("%s<= %s", separator, upper.longValue()));
                    }
                }
                result.append(";");
            } else if (param_type.equals("string")) {
                result.append("# TODO not sure how to specify a string variable");
                result.append(String.format("var %s symbolic;%n", param_name));
            } else if (param_type.equals("array")) {
                result.append(String.format("# TODO generate entries for map '%s'%n", param_name));
            }
        }
        return result.toString();
    }

    /**
     * Handle incoming solver message.
     *
     * @param solution The message from the solver, containing a field
     *  "VariableValues" that can be processed by {@link
     *  NebulousApp#rewriteKubevela}.
     */
    public void processSolution(ObjectNode solution) {
        ObjectNode variables = solution.withObjectProperty("VariableValues");
        String kubevela = rewriteKubevela(variables);
        if (isDeployed()) {
            // Recalculate node sets, tell SAL to start/stop nodes, send
            // KubeVela for redeployment
        } else {
            // Calculate node needs, tell SAL to start nodes, send KubeVela
            // for initial deployment
        }
    }

}
