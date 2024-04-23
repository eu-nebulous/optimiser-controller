package eu.nebulouscloud.optimiser.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import eu.nebulouscloud.exn.core.Publisher;
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
            // new AttributeRequirement("location", "name", RequirementOperator.EQ, "bgo"),
            new AttributeRequirement("hardware", "ram", RequirementOperator.GEQ, "8192"),
            new AttributeRequirement("hardware", "cores", RequirementOperator.GEQ, "4"));
    }

    /**
     * Produce a fresh KubeVela specification with added node affinity traits
     * and without resource specifications.
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
    public static JsonNode createDeploymentKubevela(JsonNode kubevela) {
        JsonNode result = kubevela.deepCopy();
        for (final JsonNode c : result.withArray("/spec/components")) {
            String name = c.get("name").asText();
            // Add traits
            ArrayNode traits = c.withArray("traits");
            ObjectNode trait = traits.addObject();
            trait.put("type", "affinity");
            ArrayNode nodeSelectorTerms = trait.withArray("/properties/nodeAffinity/required/nodeSelectorTerms");
            ArrayNode matchExpressions = nodeSelectorTerms.addObject().withArray("matchExpressions");
            ObjectNode term = matchExpressions.addObject();
            term.put("key", "nebulouscloud.eu/" + name)
                .put("operator", "In")
                .withArray("values").add("yes");
            // Remove resources
            c.withObject("/properties").remove("memory");
            c.withObject("/properties").remove("cpu");
            c.withObject("/properties/resources").remove("requests");
        }
        return result;
    }

    /**
     * Create a globally-unique node name.  The node name has to conform to
     * Linux hostname rules: lowercase letters, numbers and hyphens only,
     * starting with a letter.
     *
     * <p>NOTE: if the application includes components whose names only differ
     * by case or underscore vs hyphen, this method might not create unique
     * node names, which will lead to failure during cluster creation.
     *
     * @param clusterName the unique cluster name.
     * @param componentName the KubeVela component name.
     * @param deployGeneration 1 for initial deployment, increasing for each
     *  redeployment.
     * @param nodeNumber the replica number of the component to be deployed on
     *  the node.
     * @return a node name, unique if componentNames are "sufficiently unique"
     *  (see above).
     */
    public static String createNodeName(String clusterName, String componentName, int deployGeneration, int nodeNumber) {
        String nodename = String.format("n%s-%s-%s-%s", clusterName, componentName, deployGeneration, nodeNumber);
        nodename = nodename.toLowerCase();
        nodename = nodename.replaceAll("[^a-z0-9-]", "-");
        return nodename;
    }

    /**
     * Given a cluster definition (as returned by {@link
     * ExnConnector#getCluster}), return if all nodes are ready, i.e., are in
     * state {@code "Finished"}.  Once this method returns {@code true}, it is
     * safe to call {@link ExnConnector#labelNodes} and {@link
     * ExnConnector#deployApplication}.
     *
     * <p>Note that this approach will not detect when a call to the scaleIn
     * endpoint has finished, since there are no nodes to start in that case.
     * But for our workflow this does not matter, since scaling in is done
     * after scaling out, relabeling and redeploying the application, so there
     * is no other step that needs to wait for scaleIn to finishh.
     *
     * @param clusterStatus The cluster status, as returned by {@link
     *  ExnConnector#getCluster}.
     * @return {@code true} if all nodes are in state {@code "Finished"},
     *  {@code false} otherwise.
     */
    private static boolean isClusterDeploymentFinished(JsonNode clusterStatus) {
        if (clusterStatus == null || !clusterStatus.isObject())
            // Catch various failure states, e.g., SAL spuriously returning
            // null.  Persistent getClusterStatus failures need to be handled outside
            // this method.
            return false;
        return clusterStatus.withArray("/nodes")
            .findParents("state")
            .stream()
            .allMatch(node -> node.get("state").asText().equals("Finished"));
    }

    /**
     * Wait until all nodes in cluster are in state "Finished".
     *
     * <p>Note: Cluster deployment includes provisioning and booting VMs,
     * installing various software packages, bringing up a Kubernetes cluster
     * and installing the NebulOuS runtime.  This can take some minutes.
     */
    private static boolean waitForClusterDeploymentFinished(ExnConnector conn, String clusterName, String appUUID) {
        // TODO: find out what state node(s) or the whole cluster are in when
        // cluster start fails, and return false in that case.
        try {
            // Sleep a little at the beginning so that SAL has a chance to
            // initialize its data structures etc. -- don't want to call
            // getCluster immediately after deployCluster
            Thread.sleep(10000);
        } catch (InterruptedException e1) {
            // ignore
        }
        JsonNode clusterState = conn.getCluster(clusterName);
        while (clusterState == null || !isClusterDeploymentFinished(clusterState)) {
            log.info("Waiting for cluster deployment to finish...",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName),
                keyValue("clusterState", clusterState));
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e1) {
                // ignore
            }
            clusterState = conn.getCluster(clusterName);
        }
        return true;
    }

    /**
     * Given a KubeVela file, extract node requirements, create the job, start
     * its nodes and submit KubeVela.
     *
     * <p>NOTE: this method modifies the NebulousApp object state, storing
     * various facts about the deployed cluster.
     *
     * @param app The NebulOuS app object.
     * @param kubevela the KubeVela file to deploy.
     */
    public static void deployApplication(NebulousApp app, JsonNode kubevela) {
        String appUUID = app.getUUID();
        String clusterName = app.getClusterName();
        if (!app.setStateDeploying()) {
            // TODO: wait until we got the performance indicators from Marta
            log.error("Trying to deploy app that is in state {} (should be READY), aborting deployment",
                app.getState().name(),
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            return;
        }
        // The application name is typed in by the user, and is used
        // internally by SAL as an unquoted filename in a generated shell
        // script. It shouldn't be this way but it is what it is.
        String safeAppName = app.getName().replaceAll("[^a-zA-Z0-9-_]", "_");
        ExnConnector conn = app.getExnConnector();
        log.info("Starting initial deployment for application",
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));


        // The overall flow:
        //
        // - Extract node requirements and node counts from the KubeVela
        //   definition.
        // - Rewrite KubeVela: remove performance requirements, add affinity
        //   traits
        // - Ask resource broker for node candidates for all components and the
        //   controller.
        // - Select node candidates, making sure to only select edge nodes
        //   once.
        // - (Before deploying the cluster) send metric name list.
        // - Create a SAL cluster.
        // - Deploy the SAL cluster.
        // - Add node affinity traits to the KubeVela file.
        // - Deploy the SAL application.
        // - Store cluster state (deployed KubeVela file, etc.) in
        //   NebulousApp object.
        // - Asynchronously, triggered via solver readiness message: wait for
        //   solver to be ready, send AMPL and re-send metric name list.

        // ------------------------------------------------------------
        // Extract node requirements
        Map<String, List<Requirement>> componentRequirements = KubevelaAnalyzer.getBoundedRequirements(kubevela);
        Map<String, Integer> nodeCounts = KubevelaAnalyzer.getNodeCount(kubevela);
        List<Requirement> controllerRequirements = getControllerRequirements(appUUID);
        // // HACK: do this only when cloud id = nrec
        // componentRequirements.forEach(
        //     (k, reqs) -> reqs.add(new AttributeRequirement("location", "name", RequirementOperator.EQ, "bgo")));

        Main.logFile("component-requirements-" + appUUID + ".txt", componentRequirements);
        Main.logFile("component-counts-" + appUUID + ".txt", nodeCounts);
        Main.logFile("controller-requirements-" + appUUID + ".txt", controllerRequirements);

        // ------------------------------------------------------------
        // Rewrite KubeVela
        JsonNode rewritten = createDeploymentKubevela(kubevela);
        String rewritten_kubevela = "---\n# Did not manage to create rewritten KubeVela";
        try {
            rewritten_kubevela = yamlMapper.writeValueAsString(rewritten);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert KubeVela to YAML; this should never happen",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName), e);
            app.setStateFailed();
            return;
        }
        Main.logFile("rewritten-kubevela-" + appUUID + ".yaml", rewritten_kubevela);

        // ----------------------------------------
        // Find node candidates

        // TODO: filter by app resources / cloud? (check enabled: true in resources array)
        List<NodeCandidate> controllerCandidates = conn.findNodeCandidates(controllerRequirements, appUUID);
        if (controllerCandidates.isEmpty()) {
            log.error("Could not find node candidates for requirements: {}, aborting deployment",
                controllerRequirements, keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            return;
        }
        Map<String, List<NodeCandidate>> componentCandidates = new HashMap<>();
        for (Map.Entry<String, List<Requirement>> e : componentRequirements.entrySet()) {
            String nodeName = e.getKey();
            List<Requirement> requirements = e.getValue();
            // TODO: filter by app resources / cloud? (check enabled: true in resources array)
            List<NodeCandidate> candidates = conn.findNodeCandidates(requirements, appUUID);
            if (candidates.isEmpty()) {
                log.error("Could not find node candidates for for node {}, requirements: {}, aborting deployment", nodeName, requirements,
                    keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                app.setStateFailed();
                return;
            }
            componentCandidates.put(nodeName, candidates);
        }

        // ------------------------------------------------------------
        // Select node candidates

        Map<String, NodeCandidate> nodeEdgeCandidates = new HashMap<>(app.getNodeEdgeCandidates());

        // Controller node
        log.info("Deciding on controller node candidate", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        // Take care to only use lowercase, numbers, starting with letter
        String masterNodeName = "m" + clusterName.toLowerCase() + "-master";
        NodeCandidate masterNodeCandidate = null;
        if (controllerCandidates.size() > 0) {
            masterNodeCandidate = controllerCandidates.get(0);
            if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE)
                .contains(masterNodeCandidate.getNodeCandidateType())) {
                // Mark this candidate as already chosen
                nodeEdgeCandidates.put(masterNodeName, masterNodeCandidate);
            }
        } else {
            log.error("Empty node candidate list for controller, aborting deployment",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            return;
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
        Map<String, Set<String>> componentNodeNames = new HashMap<>();
        for (Map.Entry<String, List<Requirement>> e : componentRequirements.entrySet()) {
            String componentName = e.getKey();
            int numberOfNodes = nodeCounts.get(componentName);
            Set<String> nodeNames = new HashSet<>();
            List<NodeCandidate> candidates = componentCandidates.get(componentName);
            if (candidates.size() == 0) {
                log.error("Empty node candidate list for component {}, aborting deployment", componentName,
                    keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                app.setStateFailed();
                return;
            }
            for (int nodeNumber = 1; nodeNumber <= numberOfNodes; nodeNumber++) {
                String nodeName = createNodeName(clusterName, componentName, app.getDeployGeneration(), nodeNumber);
                NodeCandidate candidate = candidates.stream()
                    .filter(each -> !nodeEdgeCandidates.values().contains(each))
                    .findFirst()
                    .orElse(null);
                if (candidate == null) {
                    log.error("No available node candidate for node {} of component {}, aborting deployment", nodeNumber, componentName,
                        keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                    app.setStateFailed();
                    return;
                }
                if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE).contains(candidate.getNodeCandidateType())) {
                    nodeEdgeCandidates.put(nodeName, candidate);
                }
                clusterNodes.put(nodeName, candidate);
                nodeLabels.addObject().put(nodeName, "nebulouscloud.eu/" + componentName + "=yes");
                nodeNames.add(nodeName);
            }
            // XXX TODO do not directly mutate this value
            componentNodeNames.put(componentName, nodeNames);
        }
        Main.logFile("nodenames-" + appUUID + ".txt", componentNodeNames);
        Main.logFile("master-nodecandidate-" + appUUID + ".txt", masterNodeCandidate);
        Main.logFile("component-nodecandidates-" + appUUID + ".txt", clusterNodes);
        try {
            Main.logFile("component-labels-" + appUUID + ".txt", mapper.writeValueAsString(nodeLabels));
        } catch (JsonProcessingException e1) {
            log.error("Internal error: could not convert node labels to string (this should never happen), aborting deployment",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            return;
        }

        // ------------------------------------------------------------
        // Send metrics to EMS
        app.sendMetricList();

        // ------------------------------------------------------------
        // Create cluster

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
        // TODO: add other environment variables, also from app creation
        // message (it has an "env" array)
        log.info("Calling defineCluster", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        boolean defineClusterSuccess = conn.defineCluster(appUUID, clusterName, cluster);
        if (!defineClusterSuccess) {
            log.error("Call to defineCluster failed for message body {}, aborting deployment",
                cluster, keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            return;
        }

        // ------------------------------------------------------------
        // Deploy cluster
        log.info("Calling deployCluster", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        boolean deployClusterSuccess = conn.deployCluster(appUUID, clusterName);
        if (!deployClusterSuccess) {
            log.error("Call to deployCluster failed, trying to delete cluster and aborting deployment",
                cluster, keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            conn.deleteCluster(appUUID, clusterName);
            return;
        }

        if (!waitForClusterDeploymentFinished(conn, clusterName, appUUID)) {
            log.error("Error while waiting for deployCluster to finish, trying to delete cluster and aborting deployment",
                cluster, keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            conn.deleteCluster(appUUID, clusterName);
            return;
        }

        log.info("Cluster deployment finished, continuing with app deployment",
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));

        log.info("Calling labelCluster", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        boolean labelClusterSuccess = conn.labelNodes(appUUID, clusterName, nodeLabels);
        if (!labelClusterSuccess) {
            log.error("Call to deployCluster failed, aborting deployment",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            conn.deleteCluster(appUUID, clusterName);
            return;
        }

        // ------------------------------------------------------------
        // Deploy application

        log.info("Calling deployApplication", keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        long proActiveJobID = conn.deployApplication(appUUID, clusterName, safeAppName, rewritten_kubevela);
        log.info("deployApplication returned ProActive Job ID {}", proActiveJobID,
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        if (proActiveJobID == 0) {
            // 0 means conversion from long has failed (because of an invalid
            // response), OR a ProActive job id of 0.
            log.error("DeployApplication ProActive job ID = 0, deployApplication has probably failed; aborting deployment.",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            conn.deleteCluster(appUUID, clusterName);
            return;
        }
        // ------------------------------------------------------------
        // Update NebulousApp state

        app.setStateDeploymentFinished(componentRequirements, nodeCounts, componentNodeNames, nodeEdgeCandidates, rewritten);
        log.info("App deployment finished.",
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
    }

    /**
     * Given a KubeVela file, adapt the running application to its
     * specification.
     *
     * The KubeVela file is already rewritten with updated information from
     * the solver when this method is called, so reflects the desired new
     * state of the application cluster.
     *
     * @param app the NebulOuS app object.
     * @param updatedKubevela the KubeVela file to deploy.
     */
    public static void redeployApplication(NebulousApp app, ObjectNode updatedKubevela) {
        String appUUID = app.getUUID();
        String clusterName = app.getClusterName();
        ExnConnector conn = app.getExnConnector();
        if (!app.setStateRedeploying()) {
            log.error("Trying to redeploy app that is in state {} (should be RUNNING), aborting",
                app.getState().name(),
                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
            app.setStateFailed();
            return;
        }

        log.info("Starting redeployment generation {}", app.getDeployGeneration(),
            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
        // The overall flow:
        //
        // 1. Extract node requirements and node counts from the updated
        //    KubeVela definition.
        // 2. Calculate new (to be started) and superfluous (to be shutdown)
        //    nodes by comparing against previous deployment.
        // 3. Find node candidates for new nodes (from Step 2) according to
        //    their requirements (from Step 1)
        // 4. Call scaleOut endpoint with list of added nodes
        // 5. Call labelNodes for added nodes, to-be-removed nodes
        // 6. Call deployApplication
        // 7. call scaleIn endpoint with list of removed node names
        Main.logFile("redeploy-kubevela-" + appUUID + ".yaml", updatedKubevela);

        // ------------------------------------------------------------
        // 1. Extract node requirements
        Map<String, List<Requirement>> componentRequirements = KubevelaAnalyzer.getBoundedRequirements(updatedKubevela);
        Map<String, Integer> componentReplicaCounts = KubevelaAnalyzer.getNodeCount(updatedKubevela);

        Map<String, List<Requirement>> oldComponentRequirements = app.getComponentRequirements();
        Map<String, Integer> oldComponentReplicaCounts = app.getComponentReplicaCounts();
        Map<String, Set<String>> componentNodeNames = new HashMap<>(app.getComponentNodeNames());
        Map<String, NodeCandidate> nodeEdgeCandidates = new HashMap<>(app.getNodeEdgeCandidates());

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
                    allMachineNames = componentNodeNames.get(componentName);
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
                        String nodeName = createNodeName(clusterName, componentName, app.getDeployGeneration(), nodeNumber);
                        NodeCandidate candidate = candidates.stream()
                            .filter(each -> !nodeEdgeCandidates.values().contains(each))
                            .findFirst()
                            .orElse(null);
                        if (candidate == null) {
                            log.error("No available node candidate for node {} of component {}", nodeNumber, componentName,
                                keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                            continue;
                        }
                        if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE).contains(candidate.getNodeCandidateType())) {
                            nodeEdgeCandidates.put(nodeName, candidate);
                        }
                        nodesToAdd.addObject()
                            .put("nodeName", nodeName)
                            .put("nodeCandidateId", candidate.getId())
                            .put("cloudId", candidate.getCloud().getId());
                        nodeLabels.addObject()
                            .put(nodeName, "nebulouscloud.eu/" + componentName + "=yes");
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
                    allMachineNames = componentNodeNames.get(componentName);
                    Set<String> removedInstances = allMachineNames.stream().limit(nRemove).collect(Collectors.toSet());
                    removedInstances.forEach(nodeEdgeCandidates::remove);
                    allMachineNames.removeAll(removedInstances);
                    nodesToRemove.addAll(removedInstances);
                    removedInstances.forEach((nodeName) -> nodeLabels.addObject().put(nodeName, "nebulouscloud.eu/" + componentName + "=no"));
                } else {
                    log.debug("Nothing changed for component {}", componentName,
                        keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                    allMachineNames = componentNodeNames.get(componentName);
                }
            } else {
                // Node requirements have changed: need to shut down all
                // current machines and start fresh ones
                nodesToRemove.addAll(componentNodeNames.get(componentName));
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
                    String nodeName = createNodeName(clusterName, componentName, app.getDeployGeneration(), nodeNumber);
                    NodeCandidate candidate = candidates.stream()
                        .filter(each -> !nodeEdgeCandidates.values().contains(each))
                        .findFirst()
                        .orElse(null);
                    if (candidate == null) {
                        log.error("No available node candidate for node {} of component {}", nodeNumber, componentName,
                            keyValue("appId", appUUID), keyValue("clusterName", clusterName));
                        continue;
                    }
                    if (Set.of(NodeCandidateTypeEnum.BYON, NodeCandidateTypeEnum.EDGE).contains(candidate.getNodeCandidateType())) {
                        nodeEdgeCandidates.put(nodeName, candidate);
                    }
                    nodesToAdd.addObject()
                            .put("nodeName", nodeName)
                            .put("nodeCandidateId", candidate.getId())
                            .put("cloudId", candidate.getCloud().getId());
                    allMachineNames.add(nodeName);
                }
            }
            componentNodeNames.put(componentName, allMachineNames);
        }

        Main.logFile("redeploy-worker-requirements-" + appUUID + ".txt", componentRequirements);
        Main.logFile("redeploy-worker-counts-" + appUUID + ".txt", componentReplicaCounts);

        if (!nodesToAdd.isEmpty()) {
            conn.scaleOut(appUUID, clusterName, nodesToAdd);
            waitForClusterDeploymentFinished(conn, clusterName, appUUID);
        }

        conn.labelNodes(appUUID, clusterName, nodeLabels);

        String kubevelaString = "---\n# Did not manage to create rewritten KubeVela";
        try {
            kubevelaString = yamlMapper.writeValueAsString(updatedKubevela);
            Main.logFile("redeploy-rewritten-kubevela-" + appUUID + ".yaml", kubevelaString);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert KubeVela to YAML; this should never happen",
                keyValue("appId", appUUID), keyValue("clusterName", clusterName), e);
            app.setStateFailed();
            return;
        }
        conn.deployApplication(appUUID, clusterName, app.getName(), kubevelaString);

        if (!nodesToRemove.isEmpty()) {
            conn.scaleIn(appUUID, clusterName, nodesToRemove);
        }

        app.setStateDeploymentFinished(componentRequirements, componentReplicaCounts,
            componentNodeNames, nodeEdgeCandidates, updatedKubevela);
    }

}
