package eu.nebulouscloud.optimiser.controller;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import eu.nebulouscloud.exn.core.Publisher;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.ow2.proactive.sal.model.AttributeRequirement;
import org.ow2.proactive.sal.model.CommandsInstallation;
import org.ow2.proactive.sal.model.Communication;
import org.ow2.proactive.sal.model.IaasDefinition;
import org.ow2.proactive.sal.model.JobDefinition;
import org.ow2.proactive.sal.model.JobInformation;
import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.Requirement;
import org.ow2.proactive.sal.model.RequirementOperator;
import org.ow2.proactive.sal.model.TaskDefinition;

/**
 * Internal representation of a NebulOus app.
 */
@Slf4j
public class NebulousApp {
    
    /** Location of the kubevela yaml file in the app creation message (String) */
    private static final JsonPointer kubevela_path = JsonPointer.compile("/kubevela/original");

    /** Location of the variables (optimizable locations) of the kubevela file
     * in the app creation message. (Array of objects) */
    private static final JsonPointer variables_path = JsonPointer.compile("/kubevela/variables");

    /** Locations of the UUID and name in the app creation message (String) */
    private static final JsonPointer uuid_path = JsonPointer.compile("/application/uuid");
    private static final JsonPointer name_path = JsonPointer.compile("/application/name");
    private static final JsonPointer utility_function_path = JsonPointer.compile("/utility_functions");
    public static final JsonPointer constraints_path = JsonPointer.compile("/slo");

    /** The YAML converter */
    // Note that instantiating this is apparently expensive, so we do it only once
    private static final ObjectMapper yaml_mapper = new ObjectMapper(new YAMLFactory());

    /** General-purpose object mapper */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * The active SAL connector, or null if we operate offline.
     *
     * NOTE: this might only be used until we switch to the exn-sal
     * middleware, or maybe we keep the SalConnector class and send to exn
     * from there.
     *
     * @param salConnector the SAL connector.
     */
    @Setter @Getter
    private static SalConnector salConnector;

    /**
     * The requirements of the node running the NebulOuS controller.  This
     * machine runs the Kubernetes cluster and KubeVela.
     */
    @Getter
    private static final List<Requirement> controllerRequirements
        = List.of(
            new AttributeRequirement("hardware", "memory", RequirementOperator.GEQ, "2048"),
            new AttributeRequirement("hardware", "cpu", RequirementOperator.GEQ, "2"));

    /**
     * The UUID of the app.  This is the UUID that identifies a specific
     * application's ActiveMQ messages.
     *
     * @return the UUID of the app
     */
    @Getter
    private String UUID;
    /** The app name, a user-defined string.  Not safe to assume that this is
      * a unique value. */
    @Getter private String name;
    /** The original app message. */
    @Getter private JsonNode originalAppMessage;
    private ObjectNode original_kubevela;
    /** The array of KubeVela variables in the app message. */
    @Getter private ArrayNode kubevelaVariables;

    /** Map from AMPL variable name to location in KubeVela. */
    private Map<String, JsonPointer> kubevela_variable_paths = new HashMap<>();
    /** The app's raw metrics, a map from key to the defining JSON node. */
    @Getter private Map<String, JsonNode> rawMetrics = new HashMap<>();
    /** The app's composite metrics, a map from key to the defining JSON node. */
    @Getter private  Map<String, JsonNode> compositeMetrics = new HashMap<>();
    /** The app's performance indicators, a map from key to the defining JSON node. */
    @Getter private Map<String, JsonNode> performanceIndicators = new HashMap<>();
    /** The app's utility functions; the AMPL solver will optimize for one of these. */
    @Getter private Map<String, JsonNode> utilityFunctions = new HashMap<>();
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
     * @param ampl_message_channel A publisher for sending the generated AMPL file, or null
     */
    // Note that example KubeVela and parameter files can be found at
    // optimiser-controller/src/test/resources/
    public NebulousApp(JsonNode app_message, ObjectNode kubevela, Publisher ampl_message_channel) {
        this.UUID = app_message.at(uuid_path).textValue();
        this.name = app_message.at(name_path).textValue();
        this.originalAppMessage = app_message;
        this.original_kubevela = kubevela;
        JsonNode parameters = app_message.at(variables_path);
        if (parameters.isArray()) {
            this.kubevelaVariables = (ArrayNode)app_message.at(variables_path);
        } else {
            log.error("Cannot read parameters from app message '{}', continuing without parameters", UUID);
            this.kubevelaVariables = mapper.createArrayNode();
        }
        this.ampl_message_channel = ampl_message_channel;
        for (final JsonNode p : kubevelaVariables) {
            kubevela_variable_paths.put(p.get("key").asText(),
                yqPathToJsonPointer(p.get("path").asText()));
        }
        for (JsonNode f : originalAppMessage.withArray(utility_function_path)) {
            utilityFunctions.put(f.get("key").asText(), f);
        }

        // We need to know which metrics are raw, composite, and which ones
        // are performance indicators in disguise.
        boolean done = false;
        Set<JsonNode> metrics = StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(app_message.withArray("/metrics").elements(), Spliterator.ORDERED), false)
            .collect(Collectors.toSet());
        while (!done) {
            // Pick out all raw metrics.  Then pick out all composite metrics
            // that only depend on raw metrics and composite metrics that only
            // depend on raw metrics.  The rest are performance indicators.
            done = true;
            Iterator<JsonNode> it = metrics.iterator();
            while (it.hasNext()) {
                JsonNode m = it.next();
                if (m.get("type").asText().equals("raw")) {
                    rawMetrics.put(m.get("key").asText(), m);
                    it.remove();
                    done = false;
                } else {
                    ObjectNode mappings = m.withObject("mapping");
                    boolean is_composite_metric = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(mappings.elements(), Spliterator.ORDERED), false)
                        .allMatch(o -> rawMetrics.containsKey(o.asText()) || compositeMetrics.containsKey(o.asText()));
                    if (is_composite_metric) {
                        compositeMetrics.put(m.get("key").asText(), m);
                        it.remove();
                        done = false;
                    }
                }
            }
        }
        for (JsonNode m : metrics) {
            // What's left is neither a raw nor composite metric.
            performanceIndicators.put(m.get("key").asText(), m);
        }
        for (JsonNode f : app_message.withArray(utility_function_path)) {
            // What's left is neither a raw nor composite metric.
            utilityFunctions.put(f.get("key").asText(), f);
        }
        log.debug("New App instantiated: Name='{}', UUID='{}'", name, UUID);
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
                    ampl_message_channel);
            }
        } catch (Exception e) {
            log.error("Could not read app creation message: ", e);
            return null;
        }
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
        for (final Object p : kubevelaVariables) {
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
    public ObjectNode rewriteKubevela(ObjectNode variable_values) {
        ObjectNode fresh_kubevela = original_kubevela.deepCopy();
        for (Map.Entry<String, JsonNode> entry : variable_values.properties()) {
            // look up the prepared path in the variable |-> location map
            JsonPointer path = kubevela_variable_paths.get(entry.getKey());
            JsonNode node = fresh_kubevela.at(path);
            if (node == null) {
                log.error("Location {} not found in KubeVela, cannot replace value", entry.getKey());
            } else if (!node.getNodeType().equals(entry.getValue().getNodeType())) {
                // This could be a legitimate code path for, e.g., replacing
                // KubeVela "memory: 512Mi" with "memory: 1024" (i.e., if the
                // solution delivers a number where we had a string--note that
                // suffix-less memory specs are handled in
                // getSalRequirementsFromKubevela).  Adapt as necessary during
                // integration test.
                //
                // TODO: add the "Mi" suffix if the "meaning" field of that
                // variable entry in the app creation message is "memory".
                log.error("Trying to replace value with a value of a different type");
            } else {
                // get the parent object and the property name; replace with
                // what we got
                ObjectNode parent = (ObjectNode)fresh_kubevela.at(path.head());
                String property = path.last().getMatchingProperty();
                parent.replace(property, entry.getValue());
            }
        }
        return fresh_kubevela;
    }

    /**
     * Calculate AMPL file and send it off to the solver.
     */
    public void sendAMPL() {
        if (ampl_message_channel == null) {
            log.warn("AMPL publisher not set, cannot send AMPL file");
            return;
        }
        String ampl = AMPLGenerator.generateAMPL(this);
        ObjectNode msg = mapper.createObjectNode();
        msg.put("FileName", getUUID() + ".ampl"); // TODO: check if filename needs to be unique
        msg.put("FileContent", ampl);
        msg.put("ObjectiveFunction", getObjectiveFunction());
        ampl_message_channel.send(mapper.convertValue(msg, Map.class), getUUID());
        Main.logFile(getUUID() + "to-solver.json", msg.toString());
        Main.logFile(getUUID() + ".ampl", ampl);
    }


    /**
     * The objective function to use.  In case the app creation message
     * specifies more than one and doesn't indicate which one to use, choose
     * the first one.
     *
     * @return the objective function specified in the app creation message.
     */
    private String getObjectiveFunction() {
        ArrayNode utility_functions = originalAppMessage.withArray(utility_function_path);
        if (utility_functions.size() == 0) {
            log.warn("No utility function given in app message; solver will likely complain");
            return "";
        } else {
            return utility_functions.get(0).get("key").asText();
        }
    }

	/**
     * Handle incoming solver message.
     *
     * @param solution The message from the solver, containing a field
     *  "VariableValues" that can be processed by {@link
     *  NebulousApp#rewriteKubevela}.
     */
    public void processSolution(ObjectNode solution) {
        // TODO: check if the solution is for our application (check uuid) in
        // message; pass it in
        if (!solution.get("DeploySolution").asBoolean(false)) {
            // `asBoolean` returns its parameter if node cannot be converted to Boolean
            return;
        }
        ObjectNode variables = solution.withObjectProperty("VariableValues");
        ObjectNode kubevela = rewriteKubevela(variables);
        if (isDeployed()) {
            // We assume that killing a node will confuse the application's
            // Kubernetes cluster, therefore:
            // 1. Recalculate node sets
            // 2. Tell SAL to start fresh nodes, passing in the deployment
            //    scripts
            // 3. Send updated KubeVela for redeployment
            // 4. Shut down superfluous nodes
            
        } else {
            // 1. Calculate node sets, including Nebulous controller node
            // 2. Tell SAL to start all nodes, passing in the deployment
            //    scripts
            // 3. Send KubeVela file for deployment
            NebulousAppDeployer.startApplication(kubevela, UUID, name);
        }
    }

}
