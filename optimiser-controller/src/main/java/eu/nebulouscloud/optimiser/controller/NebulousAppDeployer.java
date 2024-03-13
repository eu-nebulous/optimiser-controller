package eu.nebulouscloud.optimiser.controller;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import eu.nebulouscloud.optimiser.kubevela.KubevelaAnalyzer;
import org.ow2.proactive.sal.model.AttributeRequirement;
import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.NodeCandidate.NodeCandidateTypeEnum;
import org.ow2.proactive.sal.model.NodeType;
import org.ow2.proactive.sal.model.NodeTypeRequirement;
import org.ow2.proactive.sal.model.OperatingSystemFamily;
import org.ow2.proactive.sal.model.Requirement;
import org.ow2.proactive.sal.model.RequirementOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import lombok.extern.slf4j.Slf4j;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

/**
 * Deploy or redeploy a NebulousApp.  This class could live as a couple of
 * methods in {@link NebulousApp} but we group all things SAL and deployment
 * in this file for better readability.
 */
@Slf4j
public class NebulousAppDeployer {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper mapper = new ObjectMapper();

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
     * <p>NOTE: this method modifies the NebulousApp object state, storing
     * various facts about the deployed cluster.
     *
     * <p>NOTE: this method is under reconstruction, pending the new
     * endpoints.
     *
     * @param app The NebulOuS app object.
     * @param kubevela the KubeVela file to deploy.
     */
    public static void deployApplication(NebulousApp app, JsonNode kubevela) {
        String appUUID = app.getUUID();
        ExnConnector conn = app.getExnConnector();
        Set<NodeCandidate> chosenEdgeCandidates = new HashSet<>();
        log.info("Starting initial deployment for application", keyValue("appId", appUUID));

        int deployGeneration = app.getDeployGeneration() + 1;
        app.setDeployGeneration(deployGeneration);

        // The overall flow:
        //
        // 1. Extract node requirements and node counts from the KubeVela
        //    definition.
        // 2. Ask resource broker for node candidates for all workers and the
        //    controller.
        // 3. Select node candidates, making sure to only select edge nodes
        //    once.
        // 4. Create a SAL cluster.
        // 5. Deploy the SAL cluster.
        // 6. Add node affinity traits to the KubeVela file.
        // 7. Deploy the SAL application.
        // 8. Store cluster state (deployed KubeVela file, etc.) in
        //    NebulousApp object.

        // ------------------------------------------------------------
        // 1. Extract node requirements
        Map<String, List<Requirement>> workerRequirements = KubevelaAnalyzer.getRequirements(kubevela);
        Map<String, Integer> nodeCounts = KubevelaAnalyzer.getNodeCount(kubevela);
        List<Requirement> controllerRequirements = getControllerRequirements(appUUID);

        Main.logFile("worker-requirements-" + appUUID + ".txt", workerRequirements);
        Main.logFile("worker-counts-" + appUUID + ".txt", nodeCounts);
        Main.logFile("controller-requirements-" + appUUID + ".txt", controllerRequirements);
        // ----------------------------------------
        // 2. Find node candidates

        List<NodeCandidate> controllerCandidates = conn.findNodeCandidates(controllerRequirements, appUUID);
        if (controllerCandidates.isEmpty()) {
            log.error("Could not find node candidates for requirements: {}",
                controllerRequirements, keyValue("appId", appUUID));
            // Continue here while we don't really deploy
            // return;
        }
        Map<String, List<NodeCandidate>> workerCandidates = new HashMap<>();
        for (Map.Entry<String, List<Requirement>> e : workerRequirements.entrySet()) {
            String nodeName = e.getKey();
            List<Requirement> requirements = e.getValue();
            List<NodeCandidate> candidates = conn.findNodeCandidates(requirements, appUUID);
            if (candidates.isEmpty()) {
                log.error("Could not find node candidates for requirements: {}", requirements,
                    keyValue("appId", appUUID));
                // Continue here while we don't really deploy
                // return;
            }
            workerCandidates.put(nodeName, candidates);
        }

        // ------------------------------------------------------------
        // 3. Select node candidates

        // Controller node
        log.debug("Deciding on controller node candidate", keyValue("appId", appUUID));
        NodeCandidate masterNodeCandidate = null;
        if (controllerCandidates.size() > 0) {
            masterNodeCandidate = controllerCandidates.get(0);
            if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE)
                .contains(masterNodeCandidate.getNodeCandidateType())) {
                // Mark this candidate as already chosen
                chosenEdgeCandidates.add(masterNodeCandidate);
            }
        } else {
            log.error("Empty node candidate list for controller, continuing without creating node",
                keyValue("appId", appUUID));
        }

        // Component nodes
        log.debug("Collecting worker nodes for {}", appUUID, keyValue("appId", appUUID));
        Map<String, NodeCandidate> nodeNameToCandidate = new HashMap<>();
        for (Map.Entry<String, List<Requirement>> e : workerRequirements.entrySet()) {
            // Here we collect two things: the flat list (hostname ->
            // candidate) to send to createCluster, and the per-component
            // hostname sets that we remember in the app object.
            String componentName = e.getKey();
            int numberOfNodes = nodeCounts.get(componentName);
            Set<String> nodeNames = new HashSet<>();
            for (int i = 1; i <= numberOfNodes; i++) {
                String nodeName = String.format("%s-%s-%s", componentName, deployGeneration, i);
                List<NodeCandidate> candidates = workerCandidates.get(componentName);

                if (candidates.size() == 0) {
                    log.error("Empty node candidate list for component ~s, continuing without creating node", componentName, keyValue("appId", appUUID));
                    continue;
                }

                NodeCandidate candidate = candidates.stream()
                    .filter(each -> !chosenEdgeCandidates.contains(each))
                    .findFirst()
                    .orElse(null);
                if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE).contains(candidate.getNodeCandidateType())) {
                    // We could remove this candidate from `candidates` here,
                    // to save skipping over already-assigned edge nodes for
                    // the next replica of this component, but we don't want
                    // to make assumptions on whether the candidate list can
                    // be modified.  Note that we have to keep track of all
                    // assigned edge nodes in any case, since they might be
                    // candidates in subsequent components.
                    chosenEdgeCandidates.add(candidate);
                }
                nodeNameToCandidate.put(nodeName, candidate);
                nodeNames.add(nodeName);
            }
            app.getComponentMachineNames().put(componentName, nodeNames);
        }
        Main.logFile("nodenames-" + appUUID + ".txt", app.getComponentMachineNames());
        Main.logFile("worker-nodes-" + appUUID + ".txt", nodeNameToCandidate);

        // ------------------------------------------------------------
        // 4. Create cluster

        String masterNodeName = "masternode"; // safe because all component node names end with a number
        ObjectNode cluster = mapper.createObjectNode();
        cluster.put("name", appUUID)
            .put("master-node", masterNodeName);
        ArrayNode nodes = cluster.withArray("nodes");
        if (masterNodeCandidate != null) {
            nodes.addObject()
                .put("nodeName", masterNodeName)
                .put("nodeCandidateId", masterNodeCandidate.getId())
                .put("cloudId", masterNodeCandidate.getCloud().getId());
        }
        nodeNameToCandidate.forEach((name, candidate) -> {
                nodes.addObject()
                    .put("nodeName", name)
                    .put("nodeCandidateId", candidate.getId())
                    .put("cloudId", candidate.getCloud().getId());
            });
        boolean defineClusterSuccess = conn.defineCluster(appUUID, masterNodeName, null);

        // ------------------------------------------------------------
        // 5. Deploy cluster
        boolean deployClusterSuccess = conn.deployCluster(appUUID);

        // ------------------------------------------------------------
        // 6. Rewrite KubeVela
        JsonNode rewritten = addNodeAffinities(kubevela, app.getComponentMachineNames());
        String rewritten_kubevela = "---\n# Did not manage to create rewritten KubeVela";
        try {
            rewritten_kubevela = yamlMapper.writeValueAsString(rewritten);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert KubeVela to YAML; this should never happen", keyValue("appId", appUUID), e);
        }
        Main.logFile("rewritten-kubevela-" + appUUID + ".yaml", rewritten_kubevela);

        // ------------------------------------------------------------
        // 7. Deploy application

        // TODO: call deployApplication endpoint

        // ------------------------------------------------------------
        // 8. Update NebulousApp state

        // TODO: store rewritten KubeVela in application object
    }

    /**
     * Given a KubeVela file, adapt the running application to its
       specification.
     *
     * The KubeVela file will have been rewritten with updated information
     * from the solver.
     *
     * NOTE: this method is under development, pending the new endpoints.
     *
     * @param app the NebulOuS app object.
     * @param kubevela the KubeVela file to deploy.
     */
    public static void redeployApplication(NebulousApp app, ObjectNode kubevela) {
        String appUUID = app.getUUID();
        int deployGeneration = app.getDeployGeneration() + 1;
        app.setDeployGeneration(deployGeneration);

        log.info("Starting redeployment generation {}", deployGeneration, keyValue("appId", appUUID));
        // The overall flow:
        //
        // 1. Extract node requirements and node counts from the updated
        //    KubeVela definition.
        // 2. Calculate new (to be started) and superfluous (to be shutdown)
        //    nodes by comparing against previous deployment.
        // 3. Find node candidates for new nodes (from Step 3) according to
        //    their requirements (from Step 1)
        // 5. Rewrite KubeVela with updated node affinities
        // 6. Call clusterScaleOut endpoint with list of added nodes
        // 7. Call deployApplication with rewritten KubeVela
        // 8. call clusterScaleIn endpoint with list of removed node names
        Main.logFile("kubevela-updated-from-solver-" + appUUID + ".yaml", kubevela);

        // ------------------------------------------------------------
        // 1. Extract node requirements
        Map<String, List<Requirement>> workerRequirements = KubevelaAnalyzer.getRequirements(kubevela);
        Map<String, Integer> nodeCounts = KubevelaAnalyzer.getNodeCount(kubevela);

        

        Main.logFile("worker-requirements-" + appUUID + ".txt", workerRequirements);
        Main.logFile("worker-counts-" + appUUID + ".txt", nodeCounts);
        
    }

}
