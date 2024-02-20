package eu.nebulouscloud.optimiser.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.ow2.proactive.sal.model.AttributeRequirement;
import org.ow2.proactive.sal.model.CommandsInstallation;
import org.ow2.proactive.sal.model.Communication;
import org.ow2.proactive.sal.model.IaasDefinition;
import org.ow2.proactive.sal.model.JobDefinition;
import org.ow2.proactive.sal.model.JobInformation;
import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.NodeType;
import org.ow2.proactive.sal.model.NodeTypeRequirement;
import org.ow2.proactive.sal.model.OperatingSystemFamily;
import org.ow2.proactive.sal.model.Requirement;
import org.ow2.proactive.sal.model.RequirementOperator;
import org.ow2.proactive.sal.model.TaskDefinition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

/**
 * Deploy or redeploy a NebulousApp.  This class could live as a couple of
 * methods in {@link NebulousApp} but we group all things SAL and deployment
 * in this file for better readability.
 */
@Slf4j
public class NebulousAppDeployer {

    // TODO: find out the commands to initialize the controller
    /**
     * The installation scripts to send to SAL for the NebulOuS controller
     * node.
     */
    @Getter
    private static CommandsInstallation controllerInstallation = new CommandsInstallation();

    private static final ObjectMapper yaml_mapper = new ObjectMapper(new YAMLFactory());

    // TODO: find out the commands to initialize the workers
    /**
     * The installation scripts to send to SAL for a NebulOuS worker node.
     */
    @Getter
    private static CommandsInstallation nodeInstallation = new CommandsInstallation();

    /**
     * The requirements of the node running the NebulOuS controller.
     * This machine runs the Kubernetes cluster and KubeVela.  For
     * now, we ask for 8GB memory and 4 cores.
     */
    public static List<Requirement> getControllerRequirements(String jobID) {
        return List.of(
            new NodeTypeRequirement(List.of(NodeType.IAAS), jobID, jobID),
            // TODO: untested; we rely on the fact that SAL has an abstraction
            // over operating systems.  See
            // https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/sal-common/src/main/java/org/ow2/proactive/sal/model/OperatingSystemFamily.java#L39
            // and
            // https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/sal-service/src/main/java/org/ow2/proactive/sal/service/nc/NodeCandidateUtils.java#L159
            new AttributeRequirement("image", "operatingSystem.family",
                RequirementOperator.IN, OperatingSystemFamily.UBUNTU.toString()),
            new AttributeRequirement("hardware", "memory", RequirementOperator.GEQ, "4096"),
            new AttributeRequirement("hardware", "cpu", RequirementOperator.GEQ, "4"));
    }

    /**
     * Given a KubeVela file, extract how many nodes to deploy for
     * each component.  Note that this can be zero, when the component
     * should not be deployed at all, e.g., when there is a cloud and
     * an edge version of the component.
     *
     * We currently look for the following component trait:
     *
     * <pre>{@code
     * traits:
     *  - type: scaler
     *    properties:
     *      replicas: 2
     * }</pre>
     *
     * @param kubevela the parsed KubeVela file.
     * @return A map from component name to number of instances to generate.
     */
    public static Map<String, Integer> getNodeCountFromKubevela (JsonNode kubevela) {
        Map<String, Integer> result = new HashMap<>();
        ArrayNode components = kubevela.withArray("/spec/components");
        for (final JsonNode c : components) {
            result.put(c.get("name").asText(), 1); // default value
            for (final JsonNode t : c.withArray("/traits")) {
                if (t.at("/type").asText().equals("scaler")
                    && t.at("/properties/replicas").canConvertToExactIntegral())
                    {
                        result.put(c.get("name").asText(),
                            t.at("/properties/replicas").asInt());
                    }
            }
        }
        return result;
    }

    /**
     * Given a KubeVela file, extract its VM requirements in a form we can
     * send to the SAL `findNodeCandidates` endpoint. <p>
     *
     * We add the requirement that OS family == Ubuntu.
     *
     * We read the following attributes for each component:
     *
     * - `properties.cpu`, `properties.requests.cpu`: round up to next integer
     *   and generate requirement `hardware.cores`
     *
     * - `properties.memory`, `properties.requests.memory`: Handle "200Mi",
     *   "0.2Gi" and bare number, convert to MB and generate requirement
     *   `hardware.memory`
     *
     * Notes:<p>
     *
     * - For the first version, we specify all requirements as "greater or
     *   equal", i.e., we might not find precisely the node candidates that
     *   are asked for. <p>
     *
     * - Related, KubeVela specifies "cpu" as a fractional value, while SAL
     *   wants the number of cores as a whole number.  We round up to the
     *   nearest integer and ask for "this or more" cores, since we might end
     *   up with “strange” numbers of cores. <p>
     *
     * @param kubevela the parsed KubeVela file.
     * @return a map of component name to (potentially empty) list of
     *  requirements for that component.  No requirements mean any node will
     *  suffice.
     */
    public static Map<String, List<Requirement>> getWorkerRequirementsFromKubevela(JsonNode kubevela) {
        Map<String, List<Requirement>> result = new HashMap<>();
        ArrayNode components = kubevela.withArray("/spec/components");
        for (final JsonNode c : components) {
            String componentName = c.get("name").asText();
            ArrayList<Requirement> reqs = new ArrayList<>();
            reqs.add(new AttributeRequirement("image", "operatingSystem.family",
                RequirementOperator.IN, OperatingSystemFamily.UBUNTU.toString()));
            JsonNode cpu = c.at("/properties/cpu");
            if (cpu.isMissingNode()) cpu = c.at("/properties/resources/requests/cpu");
            if (!cpu.isMissingNode()) {
                // KubeVela has fractional core /cpu requirements, and the
                // value might be given as a string instead of a number, so
                // parse string in all cases.
                double kubevela_cpu = -1;
                try {
                    kubevela_cpu = Double.parseDouble(cpu.asText());
                } catch (NumberFormatException e) {
                    log.warn("CPU spec in {} is not a number, value seen is {}",
                        componentName, cpu.asText());
                }
                long sal_cores = Math.round(Math.ceil(kubevela_cpu));
                if (sal_cores > 0) {
                    reqs.add(new AttributeRequirement("hardware", "cores",
                        RequirementOperator.GEQ, Long.toString(sal_cores)));
                } else {
                    // floatValue returns 0.0 if node is not numeric
                    log.warn("CPU of component {} is 0 or not a number, value seen is {}",
                        componentName, cpu.asText());
                }
            }
            JsonNode memory = c.at("/properties/memory");
            if (memory.isMissingNode()) cpu = c.at("/properties/resources/requests/memory");
            if (!memory.isMissingNode()) {;
                String sal_memory = memory.asText();
                if (sal_memory.endsWith("Mi")) {
                    sal_memory = sal_memory.substring(0, sal_memory.length() - 2);
                } else if (sal_memory.endsWith("Gi")) {
                    sal_memory = String.valueOf(Integer.parseInt(sal_memory.substring(0, sal_memory.length() - 2)) * 1024);
                } else if (!memory.isNumber()) {
                    log.warn("Unsupported memory specification in component {} :{} (wanted 'Mi' or 'Gi') ",
                        componentName,
                        memory.asText());
                    sal_memory = null;
                }
                // Fall-through: we rewrote the KubeVela file and didn't add
                // the "Mi" suffix, but it's a number
                if (sal_memory != null) {
                    reqs.add(new AttributeRequirement("hardware", "memory",
                        RequirementOperator.GEQ, sal_memory));
                }
            }
            for (final JsonNode t : c.withArray("/traits")) {
                // TODO: Check for node affinity / geoLocation / country /
                // node type (edge or cloud)
            }
            // Finally, add requirements for this job to the map
            result.put(componentName, reqs);
        }
        return result;
    }

    /**
     * Produce a fresh KubeVela specification with added node affinity traits.
     *
     * We add the following trait to all components, except those with
     * a replica count of 0:
     *
     * <pre>{@code
     * traits:
     *   - type: affinity
     *     properties:
     *       nodeAffinity:
     *         required:
     *           nodeSelectorTerms:
     *           - matchExpressions:
     *             - key: "kubernetes.io/hostname"
     *               operator: In
     *               values: ["componentname-1", "componentname-2"]
     * }</pre>
     *
     * @param kubevela the KubeVela specification to modify. This parameter is
     *  not modified.
     * @param componentMachineNames Map from component name to node names
     *  where that component should be deployed.
     * @return a fresh KubeVela specification with added nodeAffinity traits.
     */
    public static JsonNode addNodeAffinities(JsonNode kubevela, Map<String, Set<String>> componentMachineNames) {
        JsonNode result = kubevela.deepCopy();
        for (final JsonNode c : result.withArray("/spec/components")) {
            if (componentMachineNames.getOrDefault(c.get("name").asText(), Set.of()).isEmpty()){
                // Do not generate trait at all if we didn't deploy any
                // machines.  This happens if replicas is 0
                continue;
            }
            ArrayNode traits = c.withArray("traits");
            ObjectNode trait = traits.addObject();
            trait.put("type", "affinity");
            ArrayNode nodeSelectorTerms = trait.withArray("/properties/nodeAffinity/required/nodeSelectorTerms");
            ArrayNode matchExpressions = nodeSelectorTerms.addObject().withArray("matchExpressions");
            ObjectNode term = matchExpressions.addObject();
            term.put("key", "kubernetes.io/hostname")
                .put("operator", "In");
            componentMachineNames
                .getOrDefault(c.get("name").asText(), Set.of())
                .forEach(nodename -> term.withArray("values").add(nodename));
        }
        return result;
    }

    /**
     * Given a KubeVela file, extract node requirements, create the job, start
     * its nodes and submit KubeVela.
     *
     * NOTE: this method is under reconstruction, pending the new endpoints.
     *
     * @param app the NebulOuS app object.
     * @param kubevela the KubeVela file to deploy.
     */
    public static void deployApplication(NebulousApp app, JsonNode kubevela) {
        String appUUID = app.getUUID();
        log.info("Starting initial deployment for application", keyValue("appId", appUUID));

        // The overall flow:
        //
        // 1. Extract node requirements and node counts from the KubeVela
        //    definition.
        // 2. Find node candidates for all workers and the controller.
        // 3. Select node candidates.
        // 4. Create a SAL cluster.
        // 5. Deploy the SAL cluster.

        // ------------------------------------------------------------
        // 1. Extract node requirements
        Map<String, List<Requirement>> workerRequirements = getWorkerRequirementsFromKubevela(kubevela);
        Map<String, Integer> nodeCounts = getNodeCountFromKubevela(kubevela);
        List<Requirement> controllerRequirements = getControllerRequirements(appUUID);

        Main.logFile("worker-requirements-" + appUUID + ".txt", workerRequirements);
        Main.logFile("worker-counts-" + appUUID + ".txt", nodeCounts);
        Main.logFile("controller-requirements-" + appUUID + ".txt", controllerRequirements);
        // ----------------------------------------
        // 2. Find node candidates

        // TODO: switch to asking the cloud broker for candidates when it's
        // ready
        List<NodeCandidate> controllerCandidates = SalConnector.findNodeCandidates(controllerRequirements, appUUID);
        if (controllerCandidates.isEmpty()) {
            log.error("Could not find node candidates for requirements: {}", controllerRequirements);
            // Continue here while we don't really deploy
            // return;
        }
        Map<String, List<NodeCandidate>> workerCandidates = new HashMap<>();
        for (Map.Entry<String, List<Requirement>> e : workerRequirements.entrySet()) {
            String nodeName = e.getKey();
            List<Requirement> requirements = e.getValue();
            List<NodeCandidate> candidates = SalConnector.findNodeCandidates(requirements, appUUID);
            if (candidates.isEmpty()) {
                log.error("Could not find node candidates for requirements: {}", requirements);
                // Continue here while we don't really deploy
                // return;
            }
            workerCandidates.put(nodeName, candidates);
        }

        // ------------------------------------------------------------
        // 3. Select node candidates

        log.debug("Collecting worker nodes for {}", appUUID);
        Map<String, NodeCandidate> nodeNameToCandidate = new HashMap<>();
        for (Map.Entry<String, List<Requirement>> e : workerRequirements.entrySet()) {
            // Here we collect two things: the flat list (hostname ->
            // candidate) to send to createCluster, and the per-component
            // hostname sets that we remember in the app object.
            String componentName = e.getKey();
            int numberOfNodes = nodeCounts.get(componentName);
            Set<String> nodeNames = new HashSet<>();
            for (int i = 1; i <= numberOfNodes; i++) {
                String nodeName = String.format("%s-%s", componentName, i);
                nodeNames.add(nodeName);
                // TODO: Here we need to discriminate between edge and cloud
                // node candidates: we can deploy an edge node only once, but
                // cloud nodes arbitrarily often.  So if the best node
                // candidate is an edge node, we should select it and fill the
                // rest of the nodes with second-best cloud nodes.

                // TODO: make sure we only choose the same edge node once; it
                // might be in all node candidate lists :)
                if (!workerCandidates.get(componentName).isEmpty()) {
                    // should always be true, except currently we don't abort
                    // in Step 2 if we don't find candidates.
                    NodeCandidate candidate = workerCandidates.get(componentName).get(0);
                    nodeNameToCandidate.put(nodeName, candidate);
                }
            }
            app.getComponentMachineNames().put(componentName, nodeNames);
        }
        Main.logFile("nodenames-" + appUUID + ".txt", app.getComponentMachineNames());
        Main.logFile("worker-nodes-" + appUUID + ".txt", nodeNameToCandidate);

        // ------------------------------------------------------------
        // 4. Create cluster

        // TODO: call defineCluster endpoint with nodename -> candidate
        // mapping etc.

        // ------------------------------------------------------------
        // 5. Deploy cluster

        // TODO: call deployCluster endpoint

        JsonNode rewritten = addNodeAffinities(kubevela, app.getComponentMachineNames());
        String rewritten_kubevela = "---\n# Did not manage to create rewritten KubeVela";
        try {
            rewritten_kubevela = yaml_mapper.writeValueAsString(rewritten);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert KubeVela to YAML; this should never happen", e);
        }
        Main.logFile("rewritten-kubevela-" + appUUID + ".yaml", rewritten_kubevela);
        // TODO: call deployApplication endpoint
    }

    /**
     * Given a KubeVela file, adapt the running application to its specification.
     *
     * The KubeVela file will have been rewritten with updated
     * information from the solver.
     *
     * NOTE: this method is under development, pending the new endpoints.
     *
     * @param app the NebulOuS app object.
     * @param kubevela the KubeVela file to deploy.
     */
    public static void redeployApplication(NebulousApp app, ObjectNode kubevela) {
        String appUUID = app.getUUID();
        log.info("Starting redeployment of {}", appUUID);

        // The overall flow:
        //
        // 1. Extract node requirements and node counts from the updated
        //    KubeVela definition.
        // 2. Extract current nodes from running SAL job
        // 3. Calculate new (to be started) and superfluous (to be shutdown)
        //    nodes
        // 4. Find node candidates for new nodes (from Step 3) according to
        //    their requirements (from Step 1)
        // 5. Rewrite KubeVela with updated node affinities
        // 6. Call clusterScaleOut endpoint with list of added nodes
        // 7. Call deployApplication with rewritten KubeVela
        // 8. call clusterScaleIn endpoint with list of removed node names
        Main.logFile("kubevela-updated-from-solver-" + appUUID + ".yaml", kubevela);

        // ------------------------------------------------------------
        // 1. Extract node requirements
        Map<String, List<Requirement>> workerRequirements = getWorkerRequirementsFromKubevela(kubevela);
        Map<String, Integer> nodeCounts = getNodeCountFromKubevela(kubevela);
        List<Requirement> controllerRequirements = getControllerRequirements(appUUID);

        Main.logFile("worker-requirements-" + appUUID + ".txt", workerRequirements);
        Main.logFile("worker-counts-" + appUUID + ".txt", nodeCounts);
        Main.logFile("controller-requirements-" + appUUID + ".txt", controllerRequirements);

    }

}
