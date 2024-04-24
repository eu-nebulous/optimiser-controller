package eu.nebulouscloud.optimiser.controller;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import eu.nebulouscloud.exn.core.Publisher;
import lombok.Getter;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.Requirement;

/**
 * Internal representation of a NebulOus app.
 */
@Slf4j
public class NebulousApp {

    /**
     * The UUID of the app. This identifies a specific application's ActiveMQ
     * messages, etc.  Chosen by the UI, unique across a NebulOuS
     * installation.
     */
    @Getter
    private String UUID;
    /**
     * The app name, a user-readable string.  Not safe to assume that this is
     * a unique value.
     */
    @Getter private String name;

    /**
     * The cluster name.  This must be globally unique but should be short,
     * since during deployment it will be used to create instance names, where
     * AWS has a length restriction.
     */
    @Getter private String clusterName;

    /**
     * The application status.
     *
     * <ul><li>NEW: The application has been created from the GUI and is
     * waiting for the performance indicators from the utility evaluator.
     *
     * <li>READY: The application is ready for deployment.
     *
     * <li>DEPLOYING: The application is being deployed or redeployed.
     *
     * <li>RUNNING: The application is running.
     *
     * <li>FAILED: The application is in an invalid state: one or more
     * messages could not be parsed, or deployment or redeployment failed.
     * </ul>
     */
    public enum State {
        NEW,
        READY,
        DEPLOYING,
        RUNNING,
        FAILED;
    }

    @Getter
    private State state;

    // ----------------------------------------
    // App message parsing stuff

    /** Location of the kubevela yaml file in the app creation message (String) */
    private static final JsonPointer kubevela_path = JsonPointer.compile("/content");
    /** Location of the variables (optimizable locations) of the kubevela file
     * in the app creation message. (Array of objects) */
    private static final JsonPointer variables_path = JsonPointer.compile("/variables");
    /** Locations of the UUID and name in the app creation message (String) */
    private static final JsonPointer uuid_path = JsonPointer.compile("/uuid");
    private static final JsonPointer name_path = JsonPointer.compile("/title");
    private static final JsonPointer utility_function_path = JsonPointer.compile("/utilityFunctions");
    private static final JsonPointer constraints_path = JsonPointer.compile("/sloViolations");

    /** The YAML converter */
    // Note that instantiating this is apparently expensive, so we do it only once
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /** General-purpose object mapper */
    @Getter
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    // ----------------------------------------
    // AMPL stuff

    /** The array of KubeVela variables in the app message. */
    @Getter private Map<String, JsonNode> kubevelaVariables = new HashMap<>();
    /** Map from AMPL variable name to location in KubeVela. */
    private Map<String, JsonPointer> kubevelaVariablePaths = new HashMap<>();
    /** The app's raw metrics, a map from key to the defining JSON node. */
    @Getter private Map<String, JsonNode> rawMetrics = new HashMap<>();
    /** The app's composite metrics, a map from key to the defining JSON node. */
    @Getter private  Map<String, JsonNode> compositeMetrics = new HashMap<>();
    /** The app's performance indicators, a map from key to the defining JSON node. */
    @Getter private Map<String, JsonNode> performanceIndicators = new HashMap<>();
    /** The app's "relevant" performance indicators, as calculated by the
     * utility evaluator.  Initialized to empty object for testing purposes. */
    @Getter private JsonNode relevantPerformanceIndicators = jsonMapper.createObjectNode();
    /** The app's utility functions; the AMPL solver will optimize for one of these. */
    @Getter private Map<String, JsonNode> utilityFunctions = new HashMap<>();
    /**
     * The constraints that are actually relevant for the optimizer.  If a
     * constraint does not contain a variable, we cannot influence it via the
     * solver, so it should not be included in the AMPL file.
     */
    @Getter private Set<JsonNode> effectiveConstraints = new HashSet<>();

    // ----------------------------------------
    // Deployment stuff
    /** The original app message. */
    @Getter private JsonNode originalAppMessage;
    private ObjectNode originalKubevela;

    /**
     * The current "generation" of deployment.  Initial deployment sets this
     * to 1, each subsequent redeployment increases by 1.  This value is used
     * to name node instances generated during that deployment.
     */
    @Getter
    private int deployGeneration = 0;

    /**
     * Unmodifiable map of component name to node name(s) deployed for that
     * component.  Component names are defined in the KubeVela file.  We
     * assume that component names stay constant during the app's lifecycle,
     * i.e., once an application is deployed, its components will not change.
     *
     * <p>Note that this map does not include the master node, since this is not
     * specified in KubeVela.
     */
    @Getter
    private Map<String, Set<String>> componentNodeNames = Map.of();
    /**
     * Unmodifiable map from node name to deployed edge or BYON node
     * candidate.  We keep track of assigned edge candidates, since we do not
     * want to doubly-assign edge nodes.  We also store the node name, so we
     * can "free" the edge candidate when the current component gets
     * redeployed and lets go of its edge node.  (We do not track cloud node
     * candidates since these can be instantiated multiple times.)
     */
    @Getter
    private Map<String, NodeCandidate> nodeEdgeCandidates = Map.of();
    /** Unmodifiable map of component name to its requirements, as currently
      * deployed.  Each replica of a component has identical requirements. */
    @Getter
    private Map<String, List<Requirement>> componentRequirements = Map.of();
    /** Unmodifiable map of component name to its replica count, as currently
      * deployed. */
    @Getter
    private Map<String, Integer> componentReplicaCounts = Map.of();

    /** When an app gets deployed, this is where we send the AMPL file */
    private Publisher ampl_message_channel;
    // /** Have we ever been deployed?  I.e., when we rewrite KubeVela, are there
    //  * already nodes running for us? */
    // private boolean deployed = false;

    /** The KubeVela as it was most recently sent to the app's controller. */
    @Getter
    private JsonNode deployedKubevela;
    /** For each KubeVela component, the number of deployed nodes.  All nodes
      * will be identical wrt machine type etc.  Unmodifiable map. */
    @Getter
    private Map<String, Integer> deployedNodeCounts = Map.of();
    /** For each KubeVela component, the requirements for its node(s).  Unmodifiable map. */
    @Getter
    private Map<String, List<Requirement>> deployedNodeRequirements = Map.of();

    /**
     * The EXN connector for this class.  At the moment all apps share the
     * same instance, but probably every app should have their own, out of
     * thread-safety concerns.
     */
    @Getter
    private ExnConnector exnConnector;

    /**
     * Creates a NebulousApp object.
     *
     * @param app_message The whole app creation message (JSON)
     * @param kubevela A parsed representation of the deployable KubeVela App model (YAML)
     * @param ampl_message_channel A publisher for sending the generated AMPL file, or null
     */
    // Note that example KubeVela and parameter files can be found at
    // optimiser-controller/src/test/resources/
    public NebulousApp(JsonNode app_message, ObjectNode kubevela, ExnConnector exnConnector) {
        this.UUID = app_message.at(uuid_path).textValue();
        this.name = app_message.at(name_path).textValue();
        this.state = State.NEW;
        this.clusterName = NebulousApps.calculateUniqueClusterName(this.UUID);
        this.originalAppMessage = app_message;
        this.originalKubevela = kubevela;
        this.exnConnector = exnConnector;
        JsonNode parameters = app_message.at(variables_path);
        if (parameters.isArray()) {
            for (JsonNode p : parameters) {
                kubevelaVariables.put(p.get("key").asText(), p);
                kubevelaVariablePaths.put(p.get("key").asText(),
                    JsonPointer.compile(p.get("path").asText()));
            }
        } else {
            log.error("Cannot read parameters from app message, continuing without parameters");
        }
        for (JsonNode f : originalAppMessage.withArray(utility_function_path)) {
            utilityFunctions.put(f.get("name").asText(), f);
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
                    rawMetrics.put(m.get("name").asText(), m);
                    it.remove();
                    done = false;
                } else {
                    ArrayNode arguments = m.withArray("arguments");
                    boolean is_composite_metric = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(arguments.elements(), Spliterator.ORDERED), false)
                        .allMatch(o -> rawMetrics.containsKey(o.asText()) || compositeMetrics.containsKey(o.asText()));
                    if (is_composite_metric) {
                        compositeMetrics.put(m.get("name").asText(), m);
                        it.remove();
                        done = false;
                    }
                }
            }
        }
        for (JsonNode m : metrics) {
            // What's left is neither a raw nor composite metric.
            performanceIndicators.put(m.get("name").asText(), m);
        }
        for (JsonNode f : app_message.withArray(utility_function_path)) {
            // What's left is neither a raw nor composite metric.
            utilityFunctions.put(f.get("name").asText(), f);
        }
        // In the current app message, constraints is not an array.  When this
        // changes, wrap this for loop in another loop over the constraints
        // (Constraints are called sloViolations in the app message).
        for (String key : app_message.withObject(constraints_path).findValuesAsText("metricName")) {
            // Constraints that do not use variables, directly or via
            // performance indicators, will be ignored.
            if (kubevelaVariablePaths.keySet().contains(key)
                || performanceIndicators.keySet().contains(key)) {
                effectiveConstraints.add(app_message.withObject(constraints_path));
                break;
            }
        }
        log.debug("New App instantiated.");
    }

    /**
     * Create a NebulousApp object given an app creation message parsed into JSON.
     *
     * @param app_message the app creation message, including valid KubeVela
     *  YAML et al
     * @param exnConnector The EXN connector to use for sending messages to
     *  the solver etc.
     * @return a NebulousApp object, or null if `app_message` could not be
     *  parsed
     */
    public static NebulousApp newFromAppMessage(JsonNode app_message, ExnConnector exnConnector) {
        try {
            String kubevela_string = app_message.at(kubevela_path).textValue();
            String UUID = app_message.at(uuid_path).textValue();
            JsonNode parameters = app_message.at(variables_path);
            if (kubevela_string == null || !parameters.isArray()) {
                log.error("Could not find kubevela or parameters in app creation message");
                return null;
            } else {
                Main.logFile("incoming-kubevela-" + UUID + ".yaml", kubevela_string);
                return new NebulousApp(app_message,
                    (ObjectNode)readKubevelaString(kubevela_string), exnConnector);
            }
        } catch (Exception e) {
            log.error("Could not read app creation message", e);
            return null;
        }
    }

    /**
     * Set the state from NEW to READY, adding the list of relevant
     * performance indicators.  Return false if state was not READY.
     */
    @Synchronized
    public boolean setStateReady(JsonNode relevantPerformanceIndicators) {
        if (state != State.NEW) {
            return false;
        } else {
            state = State.READY;
            this.relevantPerformanceIndicators = relevantPerformanceIndicators;
            exnConnector.sendAppStatus(UUID, state);
            return true;
        }
    }

    /**
     * Set the state from READY to DEPLOYING, and increment the generation.
     *
     * @return false if deployment could not be started, true otherwise.
     */
    @Synchronized
    public boolean setStateDeploying() {
        if (state != State.READY) {
            return false;
        } else {
            state = State.DEPLOYING;
            deployGeneration++;
            exnConnector.sendAppStatus(UUID, state);
            return true;
        }
    }

    /** Set state from DEPLOYING to RUNNING and update app cluster information.
      * @return false if not in state deploying, otherwise true. */
    @Synchronized
    public boolean setStateDeploymentFinished(Map<String, List<Requirement>> componentRequirements, Map<String, Integer> nodeCounts, Map<String, Set<String>> componentNodeNames, Map<String, NodeCandidate> nodeEdgeCandidates, JsonNode deployedKubevela) {
        if (state != State.DEPLOYING) {
            return false;
        } else {
            // We keep all state read-only so we cannot modify the app object
            // before we know deployment is successful
            this.componentRequirements = Map.copyOf(componentRequirements);
            this.componentReplicaCounts = Map.copyOf(nodeCounts);
            this.componentNodeNames = Map.copyOf(componentNodeNames);
            this.deployedKubevela = deployedKubevela;
            this.nodeEdgeCandidates = Map.copyOf(nodeEdgeCandidates);
            state = State.RUNNING;
            exnConnector.sendAppStatus(UUID, state);
            return true;
        }
    }

    /**
     * Set the state from RUNNING to DEPLOYING, and increment the generation.
     *
     * @return false if redeployment could not be started, true otherwise.
     */
    @Synchronized
    public boolean setStateRedeploying() {
        if (state != State.RUNNING) {
            return false;
        } else {
            state = State.DEPLOYING;
            deployGeneration++;
            exnConnector.sendAppStatus(UUID, state);
            return true;
        }
    }


    /** Set state unconditionally to FAILED.  No more state changes will be
      * possible once the state is set to FAILED. */
    public void setStateFailed() {
        state = State.FAILED;
        exnConnector.sendAppStatus(UUID, state);
    }

    /** Utility function to parse a KubeVela string.  Can be used from jshell. */
    public static JsonNode readKubevelaString(String kubevela) throws JsonMappingException, JsonProcessingException {
        return yamlMapper.readTree(kubevela);
    }

    /** Utility function to parse KubeVela from a file.  Intended for use from jshell.
     * @throws IOException
     * @throws JsonProcessingException
     * @throws JsonMappingException */
    public static JsonNode readKubevelaFile(String path) throws JsonMappingException, JsonProcessingException, IOException {
        return readKubevelaString(Files.readString(Path.of(path), StandardCharsets.UTF_8));
    }

    /**
     * Check that all parameters have a name, type and path, and that the
     * target path can be found in the original KubeVela file.
     *
     * @return true if all requirements hold, false otherwise
     */
    public boolean validatePaths() {
        for (final JsonNode param : kubevelaVariables.values()) {
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
     * Return the location of a path in the application's KubeVela model.
     *
     * See https://datatracker.ietf.org/doc/html/rfc6901 for a specification
     * of the path format.
     *
     * @param path the path to the requested node, in JSON Pointer notation.
     * @return the node identified by the given path, or null if the path
     * cannot be followed
     */
    private JsonNode findPathInKubevela(String path) {
        JsonNode result = originalKubevela.at(path);
        return result.isMissingNode() ? null : result;
    }

    /**
     * Replace variables in the original KubeVela with values calculated by
     * the solver.  We look up the paths of the variables in the `parameters`
     * field.
     *
     * @param variableValues A JSON object with keys being variable names and
     *  their values the replacement value, for example:
     *
     *  <pre>{@code
     * {
     *   'spec_components_0_traits_0_properties_cpu': 8,
     *   'spec_components_0_traits_0_properties_memory': 4906
     * }
     * }</pre>
     *
     *  The variable names are generated by the UI and are cross-referenced
     *  with locations in the KubeVela file.
     *
     * @return the modified KubeVela YAML, or null if no KubeVela could be
     *  generated.
     */
    public ObjectNode rewriteKubevelaWithSolution(ObjectNode variableValues) {
        ObjectNode freshKubevela = originalKubevela.deepCopy();
        for (Map.Entry<String, JsonNode> entry : variableValues.properties()) {
            String key = entry.getKey();
            JsonNode replacementValue = entry.getValue();
            JsonNode param = kubevelaVariables.get(key);
            JsonPointer path = kubevelaVariablePaths.get(key);
            JsonNode nodeToBeReplaced = freshKubevela.at(path);
            boolean doReplacement = true;

            if (nodeToBeReplaced == null) {
                // Didn't find location in KubeVela file (should never happen)
                log.warn("Location {} not found in KubeVela, cannot replace with value {}",
                    key, replacementValue);
                doReplacement = false;
            } else if (param == null) {
                // Didn't find parameter definition (should never happen)
                log.warn("Variable {} not found in user input, cannot replace with value {}",
                    key, replacementValue);
                doReplacement = false;
            } else if (param.at("/meaning").asText().equals("memory")) {
                // Special case: the solver delivers a number for memory, but
                // KubeVela wants a unit.
                if (!replacementValue.asText().endsWith("Mi")) {
                    // Don't add a second "Mi", just in case the solver has
                    // become self-aware and starts adding it on its own
                    replacementValue = new TextNode(replacementValue.asText() + "Mi");
                }
            }              // Handle other special cases here, as they come up
            if (doReplacement) {
                ObjectNode parent = (ObjectNode)freshKubevela.at(path.head());
                String property = path.last().getMatchingProperty();
                parent.replace(property, replacementValue);
            }
        }
        return freshKubevela;
    }

    /**
     * Calculate AMPL file and send it off to the solver.
     *
     * <p> TODO: this should be done once from a message handler that listens
     * for an incoming "solver ready" message
     *
     * <p> TODO: also send performance indicators to solver here
     */
    public void sendAMPL() {
        String ampl_model = AMPLGenerator.generateAMPL(this);
        String ampl_data = relevantPerformanceIndicators.at("/initialDataFile").textValue();
        ObjectNode msg = jsonMapper.createObjectNode();

        msg.put("ModelFileName", getUUID() + ".ampl");
        msg.put("ModelFileContent", ampl_model);
        if (ampl_data != null && ampl_data.length() > 0) {
            msg.put("DataFileName", getUUID() + ".dat");
            msg.put("DataFileContent", ampl_data);
        }
        msg.put("ObjectiveFunction", getObjectiveFunction());
        ObjectNode constants = msg.withObject("Constants");
          // Define initial values for constant utility functions:
          // "Constants" : {
          //   <constant utility function name> : {
          //        "Variable" : <AMPL Variable Name>
          //        "Value"    : <value at the variable's path in original KubeVela>
          //   }
          // }
        for (final JsonNode function : originalAppMessage.withArray(utility_function_path)) {
            if (!(function.get("type").asText().equals("constant")))
                continue;
            // NOTE: for a constant function, we rely on the fact that the
            // function body is a single variable defined in the "Variables"
            // section and pointing to KubeVela, and the
            // `functionExpressionVariables` array contains one entry.
            JsonNode variable = function.withArray("/expression/variables").get(0);
            String variableName = variable.get("value").asText();
            JsonPointer path = kubevelaVariablePaths.get(variableName);
            JsonNode value = originalKubevela.at(path);
            ObjectNode constant = constants.withObject(function.get("name").asText());
            constant.put("Variable", variableName);
            constant.set("Value", value);
        }
        log.info("Sending AMPL files to solver");
        exnConnector.getAmplMessagePublisher().send(jsonMapper.convertValue(msg, Map.class), getUUID(), true);
        Main.logFile("to-solver-" + getUUID() + ".json", msg.toPrettyString());
        Main.logFile("to-solver-" + getUUID() + ".ampl", ampl_model);
    }

    /**
     * Send the metric list for the given app.  This is done two times: once
     * before app cluster creation to initialize EMS, once after cluster app
     * creation to initialize the solver.
     *
     * @param app The application under deployment.
     */
    public void sendMetricList() {
        Publisher metricsChannel = exnConnector.getMetricListPublisher();
        ObjectNode msg = jsonMapper.createObjectNode();
        ArrayNode metricNames = msg.withArray("/metrics");
        AMPLGenerator.getMetricList(this).forEach(metricNames::add);
        log.info("Sending metric list");
        metricsChannel.send(jsonMapper.convertValue(msg, Map.class), getUUID(), true);
        Main.logFile("metric-names-" + getUUID() + ".json", msg.toPrettyString());
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
        for (final JsonNode function : utility_functions) {
            // do not optimize a constant function
            if (!(function.get("type").asText().equals("constant"))) {
                return function.get("name").asText();
            }
        }
        log.warn("No non-constant utility function specified for application; solver will likely complain");
        return "";
    }

    /**
     * Handle an incoming solver message.  If the message has a field {@code
     * deploySolution} with value {@code true}, rewrite the original KubeVela
     * file with the contained variable values and perform initial deployment
     * or redeployment as appropriate.  Otherwise, ignore the message.
     *
     * @param solution The message from the solver, containing a field
     *  "VariableValues" that can be processed by {@link
     *  NebulousApp#rewriteKubevelaWithSolution}.
     */
    public void processSolution(ObjectNode solution) {
        if (!solution.get("DeploySolution").asBoolean(false)) {
            // `asBoolean` returns its argument if node is missing or cannot
            // be converted to Boolean
            return;
        }
        ObjectNode variables = solution.withObjectProperty("VariableValues");
        ObjectNode kubevela = rewriteKubevelaWithSolution(variables);
        if (deployGeneration > 0) {
            // We assume that killing a node will confuse the application's
            // Kubernetes cluster, therefore:
            // 1. Recalculate node sets
            // 2. Tell SAL to start fresh nodes, passing in the deployment
            //    scripts
            // 3. Send updated KubeVela for redeployment
            // 4. Shut down superfluous nodes
            NebulousAppDeployer.redeployApplication(this, kubevela);
        } else {
            // 1. Calculate node sets, including Nebulous controller node
            // 2. Tell SAL to start all nodes, passing in the deployment
            //    scripts
            // 3. Send KubeVela file for deployment
            NebulousAppDeployer.deployApplication(this, kubevela);
        }
    }

    /**
     * Deploy an application, bypassing the solver.  This just deploys the
     * unmodified KubeVela, as given by the initial app creation message.
     */
    public void deployUnmodifiedApplication() {
        NebulousAppDeployer.deployApplication(this, originalKubevela);
    }
}
