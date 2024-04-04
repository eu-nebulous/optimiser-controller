package eu.nebulouscloud.optimiser.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import eu.nebulouscloud.optimiser.kubevela.KubevelaAnalyzer;
import org.ow2.proactive.sal.model.AttributeRequirement;
import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.NodeCandidate.NodeCandidateTypeEnum;
import org.ow2.proactive.sal.model.OperatingSystemFamily;
import org.ow2.proactive.sal.model.Requirement;
import org.ow2.proactive.sal.model.RequirementOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import lombok.extern.slf4j.Slf4j;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

/**
 * Deploy or redeploy a NebulousApp.  This class could live as a couple of
 * methods in {@link NebulousApp} but we group all things SAL and deployment
 * in this file for better readability.
 */
@Slf4j
public class NebulousAppDeployer {

    private static final ObjectMapper yamlMapper
        = new ObjectMapper(YAMLFactory.builder().build());
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * The requirements of the node running the NebulOuS controller.
     * This machine runs the Kubernetes cluster and KubeVela.  For
     * now, we ask for 8GB memory and 4 cores.
     */
    public static List<Requirement> getControllerRequirements(String jobID) {
        return List.of(
            new AttributeRequirement("image", "operatingSystem.family",
                RequirementOperator.IN, OperatingSystemFamily.UBUNTU.toString()),
            new AttributeRequirement("image", "name", RequirementOperator.INC, "22"),
            new AttributeRequirement("location", "name", RequirementOperator.EQ, "bgo"),
            new AttributeRequirement("hardware", "ram", RequirementOperator.GEQ, "8192"),
            new AttributeRequirement("hardware", "cores", RequirementOperator.GEQ, "4"));
    }

    /**
     * Produce a fresh KubeVela specification with added node affinity traits.
     *
     * During deployment and redeployment, we label all nodes with {@code
     * nebulouscloud.eu/<componentname>=yes}.  (Note that with this scheme, a
     * node can have labels for multiple components if desired.)  We add the
     * following trait to all components:
     *
     * <pre>{@code
     * traits:
     *   - type: affinity
     *     properties:
     *       nodeAffinity:
     *         required:
     *           nodeSelectorTerms:
     *           - matchExpressions:
     *             - key: "nebulouscloud.eu/<componentname>"
     *               operator: In
     *               values: "yes"
     * }</pre>
     *
     * @param kubevela the KubeVela specification to modify. This parameter is
     *  not modified.
     * @return a fresh KubeVela specification with added nodeAffinity traits.
     */
    public static JsonNode addNodeAffinities(JsonNode kubevela) {
        JsonNode result = kubevela.deepCopy();
        for (final JsonNode c : result.withArray("/spec/components")) {
            String name = c.get("name").asText();
            ArrayNode traits = c.withArray("traits");
            ObjectNode trait = traits.addObject();
            trait.put("type", "affinity");
            ArrayNode nodeSelectorTerms = trait.withArray("/properties/nodeAffinity/required/nodeSelectorTerms");
            ArrayNode matchExpressions = nodeSelectorTerms.addObject().withArray("matchExpressions");
            ObjectNode term = matchExpressions.addObject();
            term.put("key", "nebulouscloud.eu/" + name)
                .put("operator", "In")
                .withArray("values").add("yes");
        }
        return result;
    }

    /**
     * Create a globally-unique node name.
     *
     * @param clusterName the unique cluster name.
     * @param componentName the KubeVela component name.
     * @param deployGeneration 1 for initial deployment, increasing for each redeployment.
     * @param nodeNumber the replica number of the component to be deployed on the node.
     * @return a fresh node name.
     */
    private static String createNodeName(String clusterName, String componentName, int deployGeneration, int nodeNumber) {
        return String.format("N%s-%s-%s-%s", clusterName, componentName, deployGeneration, nodeNumber);
    }

    /**
     * Given a cluster definition (as returned by {@link
     * ExnConnector#getCluster}), return if all nodes are ready, i.e., are in
     * state {@code "Finished"}.  Once this method returns {@code true}, it is
     * safe to call {@link ExnConnector#labelNodes} and {@link
     * ExnConnector#deployApplication}.
     *
     * @param clusterStatus The cluster status, as returned by {@link
     *  ExnConnector#getCluster}.
     * @return {@code true} if all nodes are in state {@code "Finished"},
     *  {@code false} otherwise.
     */
    private static boolean isClusterDeploymentFinished(JsonNode clusterStatus) {
        return clusterStatus.withArray("/nodes")
            .findParents("state")
            .stream()
            .allMatch(node -> node.get("state").asText().equals("Finished"));
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
        String clusterName = app.getClusterName();
        ExnConnector conn = app.getExnConnector();
        log.info("Starting initial deployment for application", keyValue("appId", appUUID), keyValue("clusterName", clusterName));

        int deployGeneration = app.getDeployGeneration() + 1;
        app.setDeployGeneration(deployGeneration);

        // The overall flow:
        //
        // 1. Extract node requirements and node counts from the KubeVela
        //    definition.
        // 2. Ask resource broker for node candidates for all components and the
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
        Map<String, List<Requirement>> componentRequirements = KubevelaAnalyzer.getClampedRequirements(kubevela);
        Map<String, Integer> nodeCounts = KubevelaAnalyzer.getNodeCount(kubevela);
        List<Requirement> controllerRequirements = getControllerRequirements(appUUID);
        componentRequirements.forEach(
            (k, reqs) -> reqs.add(new AttributeRequirement("location", "name", RequirementOperator.EQ, "bgo")));

        Main.logFile("component-requirements-" + appUUID + ".txt", componentRequirements);
        Main.logFile("component-counts-" + appUUID + ".txt", nodeCounts);
        Main.logFile("controller-requirements-" + appUUID + ".txt", controllerRequirements);

        // ----------------------------------------
        // 2. Find node candidates

        // TODO: filter by app resources (check enabled: true in resources array)
        List<NodeCandidate> controllerCandidates = conn.findNodeCandidates(controllerRequirements, appUUID);
        if (controllerCandidates.isEmpty()) {
            log.error("Could not find node candidates for requirements: {}",
                controllerRequirements, keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            // Continue here while we don't really deploy
            // return;
        }
        Map<String, List<NodeCandidate>> componentCandidates = new HashMap<>();
        for (Map.Entry<String, List<Requirement>> e : componentRequirements.entrySet()) {
            String nodeName = e.getKey();
            List<Requirement> requirements = e.getValue();
            // TODO: filter by app resources (check enabled: true in resources array)
            List<NodeCandidate> candidates = conn.findNodeCandidates(requirements, appUUID);
            if (candidates.isEmpty()) {
                log.error("Could not find node candidates for for node {}, requirements: {}", nodeName, requirements,
                    keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                // Continue here while we don't really deploy
                // return;
            }
            componentCandidates.put(nodeName, candidates);
        }

        // ------------------------------------------------------------
        // 3. Select node candidates

        // Controller node
        log.info("Deciding on controller node candidate", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        String masterNodeName = "N" + clusterName + "-masternode"; // safe because all component node names end with a number
        NodeCandidate masterNodeCandidate = null;
        if (controllerCandidates.size() > 0) {
            masterNodeCandidate = controllerCandidates.get(0);
            if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE)
                .contains(masterNodeCandidate.getNodeCandidateType())) {
                // Mark this candidate as already chosen
                app.getNodeEdgeCandidates().put(masterNodeName, masterNodeCandidate);
            }
        } else {
            log.error("Empty node candidate list for controller, continuing without creating node",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        }

        // Component nodes
        log.info("Collecting component nodes for {}", appUUID,
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        ArrayNode nodeLabels = mapper.createArrayNode();
        Map<String, NodeCandidate> clusterNodes = new HashMap<>();;
        // Here we collect multiple things:
        // - The node names for each component, in the field
        //   NebulousApp#componentNodeNames
        // - Each node name and its candidate (clusterNodes), for
        //   ExnConnector.createCluster
        // - Each node name and its label (nodeLabels), for
        //   ExnConnector.labelNodes
        for (Map.Entry<String, List<Requirement>> e : componentRequirements.entrySet()) {
            String componentName = e.getKey();
            int numberOfNodes = nodeCounts.get(componentName);
            Set<String> nodeNames = new HashSet<>();
            List<NodeCandidate> candidates = componentCandidates.get(componentName);
            if (candidates.size() == 0) {
                log.error("Empty node candidate list for component {}, continuing without creating node", componentName,
                    keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                continue;
            }
            for (int nodeNumber = 1; nodeNumber <= numberOfNodes; nodeNumber++) {
                String nodeName = createNodeName(clusterName, componentName, deployGeneration, nodeNumber);
                NodeCandidate candidate = candidates.stream()
                    .filter(each -> !app.getNodeEdgeCandidates().values().contains(each))
                    .findFirst()
                    .orElse(null);
                if (candidate == null) {
                    log.error("No available node candidate for node {} of component {}", nodeNumber, componentName,
                        keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                    continue;
                }
                if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE).contains(candidate.getNodeCandidateType())) {
                    app.getNodeEdgeCandidates().put(nodeName, candidate);
                }
                clusterNodes.put(nodeName, candidate);
                nodeLabels.addObject().put(nodeName, "nebulouscloud.eu/" + componentName + "=yes");
                nodeNames.add(nodeName);
            }
            app.getComponentNodeNames().put(componentName, nodeNames);
        }
        Main.logFile("nodenames-" + appUUID + ".txt", app.getComponentNodeNames());
        Main.logFile("master-nodecandidate-" + appUUID + ".txt", masterNodeCandidate);
        Main.logFile("component-nodecandidates-" + appUUID + ".txt", clusterNodes);
        try {
            Main.logFile("component-labels-" + appUUID + ".txt", mapper.writeValueAsString(nodeLabels));
        } catch (JsonProcessingException e1) {
            // ignore; the labelNodes method will report the same error later
        }

        // ------------------------------------------------------------
        // 4. Create cluster

        ObjectNode cluster = mapper.createObjectNode();
        cluster.put("name", clusterName)
            .put("master-node", masterNodeName);
        ArrayNode nodes = cluster.withArray("/nodes");
        if (masterNodeCandidate != null) {
            nodes.addObject()
                .put("nodeName", masterNodeName)
                .put("nodeCandidateId", masterNodeCandidate.getId())
                .put("cloudId", masterNodeCandidate.getCloud().getId());
        }
        clusterNodes.forEach((name, candidate) -> {
                nodes.addObject()
                    .put("nodeName", name)
                    .put("nodeCandidateId", candidate.getId())
                    .put("cloudId", candidate.getCloud().getId());
            });
        ObjectNode environment = cluster.withObject("/env-var");
        environment.put("APPLICATION_ID", appUUID);
        log.info("Calling defineCluster", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        boolean defineClusterSuccess = conn.defineCluster(appUUID, clusterName, cluster);
        if (!defineClusterSuccess) {
            log.error("Call to defineCluster failed, blindly continuing...",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        }

        // ------------------------------------------------------------
        // 5. Deploy cluster
        log.info("Calling deployCluster", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        boolean deployClusterSuccess = conn.deployCluster(appUUID, clusterName);
        if (!deployClusterSuccess) {
            log.error("Call to deployCluster failed, blindly continuing...",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        }

        JsonNode clusterState = conn.getCluster(clusterName);
        while (clusterState == null || !isClusterDeploymentFinished(clusterState)) {
            // Cluster deployment includes provisioning and booting VMs,
            // installing various software packages, bringing up a Kubernetes
            // cluster and installing the NebulOuS runtime.  This can take
            // some minutes.
            log.info("Waiting for cluster deployment to finish...",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName),
                keyValue("clusterState", clusterState));
            try {
		Thread.sleep(10000);
	    } catch (InterruptedException e1) {
                // ignore
	    }
            // TODO: distinguish between clusterState==null because SAL hasn't
            // set up its datastructures yet, and clusterState==null because
            // the call to getCluster failed.  In the latter case we want to
            // abort (because someone has deleted the cluster), in the former
            // case we want to continue.
            clusterState = conn.getCluster(clusterName);
        }
        log.info("Cluster deployment finished, continuing with app deployment",
            keyValue("appId", appUUID), keyValue("clusterName", clusterName),
            keyValue("clusterState", clusterState));

        log.info("Calling labelCluster", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        boolean labelClusterSuccess = conn.labelNodes(appUUID, clusterName, nodeLabels);
        if (!labelClusterSuccess) {
            log.error("Call to deployCluster failed, blindly continuing...",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        }

        // ------------------------------------------------------------
        // 6. Rewrite KubeVela
        JsonNode rewritten = addNodeAffinities(kubevela);
        String rewritten_kubevela = "---\n# Did not manage to create rewritten KubeVela";
        try {
            rewritten_kubevela = yamlMapper.writeValueAsString(rewritten);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert KubeVela to YAML; this should never happen",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName), e);
        }
        Main.logFile("rewritten-kubevela-" + appUUID + ".yaml", rewritten_kubevela);

        // ------------------------------------------------------------
        // 7. Deploy application

        log.info("Calling deployApplication", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        long proActiveJobID = conn.deployApplication(appUUID, clusterName, app.getName(), rewritten_kubevela);
        log.info("deployApplication returned ProActive Job ID {}", proActiveJobID,
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        if (proActiveJobID == 0) {
            // 0 means conversion from long has failed (because of an invalid
            // response), OR a ProActive job id of 0.
            log.warn("Job ID = 0, this means that deployApplication has probably failed.",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        }

        // ------------------------------------------------------------
        // 8. Update NebulousApp state

        // TODO: send out AMPL (must be done after deployCluster, once we know
        // how to pass the application id into the fresh cluster)

        app.setComponentRequirements(componentRequirements);
        app.setComponentReplicaCounts(nodeCounts);
        app.setDeployedKubevela(rewritten);
        log.info("App deployment finished.",
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
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
        String clusterName = app.getClusterName();
        int deployGeneration = app.getDeployGeneration() + 1;
        ExnConnector conn = app.getExnConnector();
        app.setDeployGeneration(deployGeneration);

        log.info("Starting redeployment generation {}", deployGeneration,
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        // The overall flow:
        //
        // 1. Extract node requirements and node counts from the updated
        //    KubeVela definition.
        // 2. Calculate new (to be started) and superfluous (to be shutdown)
        //    nodes by comparing against previous deployment.
        // 3. Find node candidates for new nodes (from Step 3) according to
        //    their requirements (from Step 1)
        // 4. Rewrite KubeVela with updated node affinities
        // 5. Call clusterScaleOut endpoint with list of added nodes
        // 6. Call labelNodes for added nodes
        // 7. Call deployApplication with rewritten KubeVela
        // 8. call clusterScaleIn endpoint with list of removed node names
        Main.logFile("kubevela-updated-from-solver-" + appUUID + ".yaml", kubevela);

        // ------------------------------------------------------------
        // 1. Extract node requirements
        Map<String, List<Requirement>> componentRequirements = KubevelaAnalyzer.getClampedRequirements(kubevela);
        Map<String, Integer> componentReplicaCounts = KubevelaAnalyzer.getNodeCount(kubevela);

        Map<String, List<Requirement>> oldComponentRequirements = app.getComponentRequirements();
        Map<String, Integer> oldComponentReplicaCounts = app.getComponentReplicaCounts();

        ArrayNode nodeLabels = mapper.createArrayNode();
        List<String> nodesToRemove = new ArrayList<>();
        ArrayNode nodesToAdd = mapper.createArrayNode();

        // We know that the component names are identical and that the maps
        // contain all keys, so it's safe to iterate through the keys of one
        // map and use it in all maps.
        for (String componentName : componentRequirements.keySet()) {
            // The variable `allMachineNames` shall, at the end of the loop
            // body, contain the machine names for this component.
            Set<String> allMachineNames;
            List<Requirement> oldR = oldComponentRequirements.get(componentName);
            List<Requirement> newR = componentRequirements.get(componentName);
            if (oldR.containsAll(newR) && newR.containsAll(oldR)) {
                // Requirements did not change
                int oldCount = oldComponentReplicaCounts.get(componentName);
                int newCount = componentReplicaCounts.get(componentName);
                if (newCount > oldCount) {
                    int nAdd = newCount - oldCount;
                    allMachineNames = app.getComponentNodeNames().get(componentName);
                    log.debug("Adding {} nodes to component {}", nAdd, componentName,
                        keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                    // TODO: filter by app resources (check enabled: true in resources array)
                    List<NodeCandidate> candidates = conn.findNodeCandidates(newR, appUUID);
                    if (candidates.isEmpty()) {
                        log.error("Could not find node candidates for requirements: {}",
                            newR, keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                        continue;
                    }
                    for (int nodeNumber = 1; nodeNumber <= nAdd; nodeNumber++) {
                        String nodeName = createNodeName(clusterName, componentName, deployGeneration, nodeNumber);
                        NodeCandidate candidate = candidates.stream()
                            .filter(each -> !app.getNodeEdgeCandidates().values().contains(each))
                            .findFirst()
                            .orElse(null);
                        if (candidate == null) {
                            log.error("No available node candidate for node {} of component {}", nodeNumber, componentName,
                                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                            continue;
                        }
                        if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE).contains(candidate.getNodeCandidateType())) {
                            app.getNodeEdgeCandidates().put(nodeName, candidate);
                        }
                        nodesToAdd.addObject()
                            .put("nodeName", nodeName)
                            .put("nodeCandidateId", candidate.getId())
                            .put("cloudId", candidate.getCloud().getId());
                        nodeLabels.addObject()
                            .put(nodeName, "nebulouscloud.eu/" + componentName + "=true");
                        allMachineNames.add(nodeName);
                    }
                } else if (newCount < oldCount) {
                    // We could be smarter and compute all scaleIn operations
                    // first, which would potentially free edge nodes that we
                    // could then reassign during subsequent scaleOut.
                    // Something for version 2.
                    int nRemove = oldCount - newCount;
                    log.debug("Removing {} nodes from component {}", nRemove, componentName,
                        keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                    // We could be a bit smarter here: remove cloud instances
                    // first and keep edge nodes in use, on the assumption
                    // that it's better to keep using edge nodes since cloud
                    // nodes incur a cost.
                    allMachineNames = app.getComponentNodeNames().get(componentName);
                    Set<String> removedInstances = allMachineNames.stream().limit(nRemove).collect(Collectors.toSet());
                    removedInstances.forEach(app.getNodeEdgeCandidates()::remove);
                    allMachineNames.removeAll(removedInstances);
                    nodesToRemove.addAll(removedInstances);
                } else {
                    log.debug("Nothing changed for component {}", componentName,
                        keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                    allMachineNames = app.getComponentNodeNames().get(componentName);
                }
            } else {
                nodesToRemove.addAll(app.getComponentNodeNames().get(componentName));
                allMachineNames = new HashSet<>();
                log.debug("Redeploying all nodes of component {}", componentName,
                    keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                // TODO: filter by app resources (check enabled: true in resources array)
                List<NodeCandidate> candidates = conn.findNodeCandidates(newR, appUUID);
                if (candidates.size() == 0) {
                    log.error("Empty node candidate list for component {}, continuing without creating node", componentName,
                        keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                    continue;
                }
                for (int nodeNumber = 1; nodeNumber <= componentReplicaCounts.get(componentName); nodeNumber++) {
                    String nodeName = createNodeName(clusterName, componentName, deployGeneration, nodeNumber);
                    NodeCandidate candidate = candidates.stream()
                        .filter(each -> !app.getNodeEdgeCandidates().values().contains(each))
                        .findFirst()
                        .orElse(null);
                    if (candidate == null) {
                        log.error("No available node candidate for node {} of component {}", nodeNumber, componentName,
                            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                        continue;
                    }
                    if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE).contains(candidate.getNodeCandidateType())) {
                        app.getNodeEdgeCandidates().put(nodeName, candidate);
                    }
                    nodesToAdd.addObject()
                            .put("nodeName", nodeName)
                            .put("nodeCandidateId", candidate.getId())
                            .put("cloudId", candidate.getCloud().getId());
                    allMachineNames.add(nodeName);
                }
            }
            app.getComponentNodeNames().put(componentName, allMachineNames);
        }

        Main.logFile("worker-requirements-" + appUUID + ".txt", componentRequirements);
        Main.logFile("worker-counts-" + appUUID + ".txt", componentReplicaCounts);

        // Call `scaleOut` with nodesToAdd

        // Call `labelNodes` with nodeLabels

        // Call `deployApplication`

        // Call `scaleIn` with nodesToRemove

        // Update app status
    }

}
