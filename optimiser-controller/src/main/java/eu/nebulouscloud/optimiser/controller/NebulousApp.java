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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    @Setter
    private static SalConnector salConnector;

    private static final List<Requirement> controller_requirements
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
    /** The app name; used as SAL job name as well */
    private String app_name;
    private JsonNode original_app_message;
    private ObjectNode original_kubevela;
    private ArrayNode kubevela_variables;

    /** Map from AMPL variable name to location in KubeVela. */
    private Map<String, JsonPointer> kubevela_variable_paths = new HashMap<>();
    /** Raw metrics. */
    private Map<String, JsonNode> raw_metrics = new HashMap<>();
    private Map<String, JsonNode> composite_metrics = new HashMap<>();
    private Map<String, JsonNode> performance_indicators = new HashMap<>();

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
        for (final JsonNode p : parameters) {
            kubevela_variable_paths.put(p.get("key").asText(),
                yqPathToJsonPointer(p.get("path").asText()));
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
                    raw_metrics.put(m.get("key").asText(), m);
                    it.remove();
                    done = false;
                } else {
                    ObjectNode mappings = m.withObject("mapping");
                    boolean is_composite_metric = StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(mappings.elements(), Spliterator.ORDERED), false)
                        .allMatch(o -> raw_metrics.containsKey(o.asText()) || composite_metrics.containsKey(o.asText()));
                    if (is_composite_metric) {
                        composite_metrics.put(m.get("key").asText(), m);
                        it.remove();
                        done = false;
                    }
                }
            }
        }
        for (JsonNode m : metrics) {
            // What's left is neither a raw nor composite metric.
            performance_indicators.put(m.get("key").asText(), m);
        }
        this.UUID = app_message.at(uuid_path).textValue();
        this.app_name = app_message.at(name_path).textValue();
        log.info("New App instantiated: Name='{}', UUID='{}'", app_name, UUID);
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
     * Calculate AMPL file and send it off to the right channel.
     */
    public void sendAMPL() {
        if (ampl_message_channel == null) {
            log.error("AMPL publisher not set, cannot send AMPL file");
            return;
        }
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
        final StringWriter result = new StringWriter();
        final PrintWriter out = new PrintWriter(result);
        out.println("# AMPL file for application with id " + getUUID());
        out.println();

        out.println("# Variables");
        for (final JsonNode p : kubevela_variables) {
            ObjectNode param = (ObjectNode) p;
            String param_name = param.get("key").textValue();
            String param_path = param.get("path").textValue();
            String param_type = param.get("type").textValue();
            ObjectNode value = (ObjectNode)param.get("value");
            if (param_type.equals("float")) {
                out.format("var %s", param_name);
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    // `isNumber` because the constraint might be given as integer
                    if (lower.isNumber()) {
                        out.format(" >= %s", lower.doubleValue());
                        separator = ", ";
                    }
                    if (upper.isNumber()) {
                        out.format("%s<= %s", separator, upper.doubleValue());
                    }
                }
                out.format(";	# %s%n", param_path);
            } else if (param_type.equals("int")) {
                out.format("var %s integer", param_name);
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    if (lower.isIntegralNumber()) {
                        out.format(" >= %s", lower.longValue());
                        separator = ", ";
                    }
                    if (upper.isIntegralNumber()) {
                        out.format("%s<= %s", separator, upper.longValue());
                    }
                }
                out.format(";	# %s%n", param_path);
            } else if (param_type.equals("string")) {
                out.println("# TODO not sure how to specify a string variable");
                out.format("var %s symbolic;	# %s%n", param_name, param_path);
            } else if (param_type.equals("array")) {
                out.format("# TODO generate entries for map '%s' at %s%n", param_name, param_path);
            }
        }
        out.println();

        out.println("# Raw metrics");
        out.println("# TODO: here we should also have initial values!");
        for (final JsonNode m : raw_metrics.values()) {
            out.format("param %s;	# %s%n", m.get("key").asText(), m.get("name").asText());
        }
        out.println();

        out.println("# Composite metrics");
        out.println("# TODO: here we should also have initial values!");
        for (final JsonNode m : composite_metrics.values()) {
            out.format("param %s;	# %s%n", m.get("key").asText(), m.get("name").asText());
        }
        out.println();

        out.println("# Performance indicators = composite metrics that have at least one variable in their formula");
        for (final JsonNode m : performance_indicators.values()) {
            String formula = replaceVariables(m.get("formula").asText(), m.withObject("mapping"));
            out.format("# %s : %s%n", m.get("name").asText(), m.get("formula").asText());
            out.format("param %s = %s;%n", m.get("key").asText(), formula);
        }
        out.println();

        out.println("# TBD: cost parameters - for all components! and use of node-candidates tensor");
        out.println();

        out.println("# Utility functions");
        for (JsonNode f : original_app_message.withArray("/utility_functions")) {
            String formula = replaceVariables(f.get("formula").asText(), f.withObject("mapping"));
            out.format("# %s : %s%n", f.get("name").asText(), f.get("formula").asText());
            out.format("%s %s :%n	%s;%n",
                f.get("type").asText(), f.get("key").asText(),
                formula);
        }
        out.println();

        out.println("# Default utility function: tbd");
        out.println();
        out.println("# Constraints. For constraints we don't have name from GUI, must be created");
        out.println("# TODO: generate from 'slo' hierarchical entry");
        return result.toString();
    }

    /**
     * Replace variables in formulas.
     *
     * @param formula a string like "A + B".
     * @param mappings an object with mapping from variables to their
     *  replacements.
     * @return the formula, with all variables replaced.
     */
    private String replaceVariables(String formula, ObjectNode mappings) {
        // If AMPL needs more rewriting of the formula than just variable name
        // replacement, we should parse the formula here.  For now, since
        // variables are word-shaped, we can hopefully get by with regular
        // expressions on the string representation of the formula.
        StringBuilder result = new StringBuilder(formula);
        Pattern id = Pattern.compile("\\b(\\w+)\\b");
        Matcher matcher = id.matcher(formula);
        int lengthDiff = 0;
        while (matcher.find()) {
            String var = matcher.group(1);
            JsonNode re = mappings.get(var);
            if (re != null) {
                int start = matcher.start(1) + lengthDiff;
                int end = matcher.end(1) + lengthDiff;
                result.replace(start, end, re.asText());
                lengthDiff += re.asText().length() - var.length();
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
        ObjectNode kubevela = rewriteKubevela(variables);
        if (isDeployed()) {
            // Recalculate node sets, tell SAL to start/stop nodes, send
            // KubeVela for redeployment
        } else {
            // Calculate node needs, tell SAL to start nodes, send KubeVela
            // for initial deployment
        }
    }

    /**
     * Given a KubeVela file, extract its VM requirements in a form we can
     * send to the SAL `findNodeCandidates` endpoint.
     *
     * Notes:
     *
     * - For the first version, we specify all requirements as "greater or
     *   equal", i.e., we might not find precisely the node candidates that
     *   are asked for.
     *
     * - Related, KubeVela specifies "cpu" as a fractional value, while SAL
     *   wants the number of cores as a whole number.  We round up to the
     *   nearest integer and ask for "this or more" cores.  Otherwise we might
     *   end up asking for instances with 3 cores, which might not exist.
     *
     * @param kubevela the parsed KubeVela file.
     * @return a map of component name to (potentially empty) list of
     *  requirements for that component.
     */
    public static Map<String, List<Requirement>> getSalRequirementsFromKubevela(JsonNode kubevela) {
        Map<String, List<Requirement>> result = new HashMap<>();
        ArrayNode components = kubevela.withArray("/spec/components");
        for (final JsonNode c : components) {
            ArrayList<Requirement> reqs = new ArrayList<>();
            result.put(c.get("name").asText(), reqs);
            JsonNode properties = c.path("properties");
            if (properties.has("cpu")) {
                // KubeVela has fractional core /cpu requirements
                String kubevela_cpu_str = properties.get("cpu").asText();
                // TODO: catch NumberFormatException
                double kubevela_cpu = Double.parseDouble(kubevela_cpu_str);
                long sal_cores = Math.round(Math.ceil(kubevela_cpu));
                if (sal_cores > 0) {
                    reqs.add(new AttributeRequirement("hardware", "cores",
                        RequirementOperator.GEQ, Long.toString(sal_cores)));
                } else {
                    // floatValue returns 0.0 if node is not numeric
                    log.error("CPU of component {} is 0 or not a number", c.get("name").asText());
                }
            }
            if (properties.has("memory")) {;
                String sal_memory = properties.get("memory").asText();
                if (sal_memory.endsWith("Mi")) {
                    sal_memory = sal_memory.substring(0, sal_memory.length() - 2);
                } else if (sal_memory.endsWith("Gi")) {
                    sal_memory = String.valueOf(Integer.parseInt(sal_memory.substring(0, sal_memory.length() - 2)) * 1024);
                } else if (!properties.get("memory").isNumber()) {
                    log.error("Unsupported memory specification in component {} :{} (wanted 'Mi' or 'Gi') ",
                        properties.get("name").asText(),
                        properties.get("memory").asText());
                    sal_memory = null;
                }
                if (sal_memory != null) {
                    reqs.add(new AttributeRequirement("hardware", "memory",
                        RequirementOperator.GEQ, sal_memory));
                }
            }
            for (final JsonNode t : c.withArray("traits")) {
                // Check for node affinity / geoLocation / country
            }
        }
        return result;
    }

    /**
     * Start application with default (not rewritten) KubeVela.
     */
    public void startApplication() {
        log.info("Starting application {} with original KubeVela", UUID);
        startApplication(original_kubevela);
    }

    /**
     * Given a KubeVela job, extract node requirements, create the job, start its
     * nodes and submit KubeVela.
     */
    public void startApplication(JsonNode kubevela) {
        log.info("Starting application {} with KubeVela", UUID);
        if (salConnector == null) {
            log.error("Tried to submit job, but do not have a connection to SAL");
            return;
        }
        // The overall flow:
        // 1. Create a SAL job, with the uuid and name of the NebulOuS app
        // 2. Extract node requirements from the KubeVela definition
        // 3. Create a coordinator node; this will run the Kubernetes
        //    controller.  This node is in addition to the nodes required by
        //    KubeVela
        // 4. Submit the job, thereby starting the coordinator node
        // 5. Extract information (IP address, ...) from the coordinator node
        // 6. Add the worker nodes, as specified by KubeVela, to the job
        // 7. Rewrite the KubeVela file to add node affinities, etc.
        // 8. Send the KubeVela file to the coordinator node

        // ------------------------------------------------------------
        // 1. Create SAL job
        log.info("Creating job info");
        JobInformation jobinfo = new JobInformation(UUID, app_name);
        // TODO: figure out what ports to specify here
        List<Communication> communications = List.of();
        // This task is deployed on the controller node (the one not specified
        // in the app KubeVela file)
        // TODO: find out the commands to initialize the controller
        // TODO: specify ubuntu in CommandsInstallation operatingSystem
        //       argument (class OperatingSystemType)
        CommandsInstallation nebulous_controller_init = new CommandsInstallation();
        TaskDefinition nebulous_controller_task = new TaskDefinition(
            "nebulous-controller", nebulous_controller_init, List.of());
        // This task is deployed on all worker nodes (the ones specified by
        // the app KubeVela file and optimized by NebulOuS)
        // TODO: find out the commands to initialize the workers
        // TODO: find out how to modify `nebulous_worker_task` to pass in
        //       information about the controller
        CommandsInstallation nebulous_worker_init = new CommandsInstallation();
        TaskDefinition nebulous_worker_task = new TaskDefinition(
            "nebulous-worker", nebulous_worker_init, List.of());
        List<TaskDefinition> tasks = List.of(nebulous_controller_task, nebulous_worker_task);
        JobDefinition job = new JobDefinition(communications, jobinfo, tasks);
        Boolean success = salConnector.createJob(job);
        if (!success) {
            // This can happen if the job has already been submitted
            log.error("Error trying to create the job; SAL createJob returned {}", success);
            log.info("Check if a job with id {} already exists, run stopJobs if yes", UUID);
            return;
        }

        // ------------------------------------------------------------
        // 2. Extract node requirements
        Map<String, List<Requirement>> requirements = getSalRequirementsFromKubevela(kubevela);

        // ------------------------------------------------------------
        // 3. Create coordinator node
        log.info("Creating app coordinator node");
        List<NodeCandidate> controller_candidates = salConnector.findNodeCandidates(controller_requirements);
        if (controller_candidates.isEmpty()) {
            log.error("Could not find node candidates for controller node; requirements: {}", controller_requirements);
            return;
        }
        NodeCandidate controller_candidate = controller_candidates.get(0);
        
        IaasDefinition controller_def = new IaasDefinition(
            "nebulous-controller-node", "nebulous-controller",
            controller_candidate.getId(), controller_candidate.getCloud().getId());
        success = salConnector.addNodes(List.of(controller_def), UUID);
        if (!success) {
            log.error("Failed to add controller node: {}", controller_candidate);
            return;
        }

        // ------------------------------------------------------------
        // 4. Submit job
        log.info("Starting job");
        String return_job_id = salConnector.submitJob(UUID);
        if (return_job_id.equals("-1")) {
            log.error("Failed to add start job {}, SAL returned {}",
                UUID, return_job_id);
            return;
        }

        // ------------------------------------------------------------
        // 5. Extract coordinator node information

        // TODO

        // ------------------------------------------------------------
        // 6. Create worker nodes from requirements
        log.info("Starting worker nodes");
        for (Map.Entry<String, List<Requirement>> e : requirements.entrySet()) {
            List<NodeCandidate> candidates = salConnector.findNodeCandidates(e.getValue());
            if (candidates.isEmpty()) {
                log.error("Could not find node candidates for requirements: {}", e.getValue());
                return;
            }
            NodeCandidate candidate = candidates.get(0);
            IaasDefinition def = new IaasDefinition(
                e.getKey(), "nebulous-worker", candidate.getId(), candidate.getCloud().getId()
            );
            // TODO: can we collect all nodes app-wide and submit them at once?
            success = salConnector.addNodes(List.of(def), UUID);
            if (!success) {
                log.error("Failed to add node: {}", candidate);
            }
        }

        // ------------------------------------------------------------
        // 7. Rewrite KubeVela file, based on running node names

        // TODO

        // ------------------------------------------------------------
        // 8. Submit KubeVela file to coordinator node

        // TODO
    }

}
