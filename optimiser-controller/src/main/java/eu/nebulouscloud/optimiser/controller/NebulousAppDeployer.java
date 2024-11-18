package eu.nebulouscloud.optimiser.controller;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.ow2.proactive.sal.model.NodeType;
import org.ow2.proactive.sal.model.NodeTypeRequirement;
import org.ow2.proactive.sal.model.Requirement;
import org.ow2.proactive.sal.model.RequirementOperator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Deploy or redeploy a NebulousApp.  This class could live as a couple of
 * methods in {@link NebulousApp} but we group all things SAL and deployment
 * in this file for better readability.
 */
@Slf4j
public class NebulousAppDeployer {

    // Copied verbatim from
    // https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/sal-service/src/main/java/org/ow2/proactive/sal/service/service/ClusterService.java
    // since there is no public definition or documentation of the cluster
    // state values

    // BEGIN COPY

    // Define cluster state constants
    private static final String STATUS_DEFINED = "Defined";

    private static final String STATUS_DEPLOYED = "Deployed";

    private static final String STATUS_FAILED = "Failed";

    private static final String STATUS_SUBMITTED = "Submitted"; // New status

    private static final String STATUS_SCALING = "Scaling";

    private static final String STATUS_FINISHED = "Finished";

    // END COPY

    private static final ObjectMapper yamlMapper
        = new ObjectMapper(YAMLFactory.builder().build());
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * The requirements of the node running the NebulOuS controller.
     * This machine runs the Kubernetes cluster and KubeVela.  For
     * now, we ask for 8GB memory and 4 cores.
     */
    public static List<Requirement> getControllerRequirements(String jobID) {
        List<Requirement> reqs = new ArrayList<>(
            Arrays.asList(
                new AttributeRequirement("hardware", "ram", RequirementOperator.GEQ, "8192"),
                new AttributeRequirement("hardware", "cores", RequirementOperator.GEQ, "4")));
        return reqs;
    }

    /**
     * Given a list of requirements for a component, create one list each for
     * each of the locations the component can be deployed on.  This
     * transforms a list of requirements suitable for {@link
     * ExnConnector#findNodeCandidates} into a value suitable for {@link
     * ExnConnector#findNodeCandidatesMultiple}.<p>
     *
     * If the component can be deployed on cloud nodes, add one requirement
     * list for each cloud provider: request node candidates from the cloud
     * located on the regions of that cloud.<p>
     *
     * If the node can be deployed on edge nodes, add two requirement lists:
     * one asking for edge nodes whose name looks like {@code
     * application_id|all-applications|<edge_device_id>} and one asking for
     * edge nodes whose name looks like {@code
     * application_id|<application_id>|<edge_device_id>}.
     *
     * @param requirements the component requirements (cpu, ram, ...)
     * @param appId the application id
     * @param clouds the clouds that the application can deploy on
     * @param location placement specification for the component
     * @return A list of lists of requirements, one per location where the component can be placed
     */
    private static List<List<Requirement>> requirementsWithLocations (
        List<Requirement> requirements,
        String appId,
        Map<String, Set<String>> clouds,
        ComponentLocationType location)
    {
        List<List<Requirement>> result = new ArrayList<>();
        if (location != ComponentLocationType.EDGE_ONLY) {
            clouds.forEach((id, regions) -> {
                List<Requirement> cloud_reqs = new ArrayList<>(requirements);
                cloud_reqs.add(new NodeTypeRequirement(List.of(NodeType.IAAS), "", ""));
                cloud_reqs.add(new AttributeRequirement("cloud", "id", RequirementOperator.EQ, id));
                if (!regions.isEmpty()) {
                    cloud_reqs.add(new AttributeRequirement("location", "name", RequirementOperator.IN, String.join(" ", regions)));
                }
                result.add(cloud_reqs);
            });
        }
        if (location != ComponentLocationType.CLOUD_ONLY) {
            String orgWideName = "application_id|all-applications|";
            List<Requirement> org_edge_reqs = new ArrayList<>(requirements);
            org_edge_reqs.add(new NodeTypeRequirement(List.of(NodeType.EDGE), "", ""));
            org_edge_reqs.add(new AttributeRequirement("hardware", "name", RequirementOperator.INC, orgWideName));
            result.add(org_edge_reqs);
            String appAssignedName = "application_id|" + appId + "|";
            List<Requirement> app_edge_reqs = new ArrayList<>(requirements);
            app_edge_reqs.add(new NodeTypeRequirement(List.of(NodeType.EDGE), "", ""));
            app_edge_reqs.add(new AttributeRequirement("hardware", "name", RequirementOperator.INC, appAssignedName));
            result.add(app_edge_reqs);
        }
        return result;
    }

    // public for testability
    public enum ComponentLocationType {
        EDGE_ONLY,
        CLOUD_ONLY,
        EDGE_AND_CLOUD
    }

    /**
     * Return the placement constraint for the given component.  We look for
     * an annotation trait as follows:
     *
     * <pre>{@code
     * traits:
     *   - type: annotations
     *     properties:
     *       nebulous-placement-constraint: CLOUD # can be CLOUD, EDGE, ANY
     * }</pre>
     */
    public static ComponentLocationType getComponentLocation(JsonNode component) {
        for (final JsonNode t : component.withArray("/traits")) {
            if (t.at("/type").asText().equals("annotations")) {
                String location = t.at("/properties/nebulous-placement-constraint").asText("ANY");
                switch (location) {
                    case "EDGE": return ComponentLocationType.EDGE_ONLY;
                    case "CLOUD": return ComponentLocationType.CLOUD_ONLY;
                    case "ANY": return ComponentLocationType.EDGE_AND_CLOUD;
                    default:
                        log.warn("Unknown nebulous-placement-constraint {} for component {}, assuming no placement constraint", location, component.at("/").asText());
                        return ComponentLocationType.EDGE_AND_CLOUD;
                }
            }
        }
        return ComponentLocationType.EDGE_AND_CLOUD;
    }

    /**
     * Produce a fresh KubeVela specification with added node affinity traits
     * and without resource specifications.
     *
     * During deployment and redeployment, we label all nodes with {@code
     * nebulouscloud.eu/<componentname>=yes}.  (Note that with this scheme, a
     * node can have labels for multiple components if desired.)  We add the
     * following trait to all normal components:
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
     * Persistent volume components do not get an affinity trait.  Serverless
     * components get an affinity for the {@code type: serverless-platform}
     * node.
     *
     * @param kubevela the KubeVela specification to modify. This parameter is
     *  not modified.
     * @return a fresh KubeVela specification with added nodeAffinity traits.
     */
    public static ObjectNode createDeploymentKubevela(JsonNode kubevela) throws IllegalStateException {
        final ObjectNode result = kubevela.deepCopy();
        final ArrayNode components = result.withArray("/spec/components");
        final List<String> serverlessPlatformNodes = KubevelaAnalyzer.findServerlessPlatformNames(result);
        if (serverlessPlatformNodes.size() > 1) {
            log.warn("More than one serverless platform node found, serverless components will run on {}", serverlessPlatformNodes.get(0));
        }
        for (final JsonNode c : components) {
            if (KubevelaAnalyzer.isVolumeComponent(c)) {
                // Persistent volume component: skip
                continue;
            } else if (KubevelaAnalyzer.isServerlessComponent(c)) {
                // Serverless component: add trait to deploy on serverless-platform node
                if (serverlessPlatformNodes.isEmpty()) {
                    throw new IllegalStateException("Trying to deploy serverless component without defining a serverless platform node");
                }
                ArrayNode traits = c.withArray("traits");
                ObjectNode trait = traits.addObject();
                trait.put("type", "affinity");
                ArrayNode nodeSelectorTerms = trait.withArray("/properties/nodeAffinity/required/nodeSelectorTerms");
                ArrayNode matchExpressions = nodeSelectorTerms.addObject().withArray("matchExpressions");
                ObjectNode term = matchExpressions.addObject();
                // TODO: figure out how to express multiple affinities; in
                // case of multiple serverless-platform nodes, we want
                // kubernetes to choose an arbitrary one.
                term.put("key", "nebulouscloud.eu/" + serverlessPlatformNodes.get(0))
                    .put("operator", "In")
                    .withArray("values").add("yes");
            } else {
                // Normal component: add trait to deploy on its own machine
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
     * Wait until cluster deployment is finished.
     *
     * <p>Note: Cluster deployment includes provisioning and booting VMs,
     * installing various software packages, bringing up a Kubernetes cluster
     * and installing the NebulOuS runtime.  This can take some minutes.
     * Depending on the status of the {@code status} field in the getCluster
     * endpoint return value, we do the following:
     *
     * <ul>
     * <li> {@code submited}: wait 10 seconds, then poll again.
     * <li> {@code deployed}: return {@code true}.
     * <li> {@code failed}: return {@code false}.
     * <li> others: warn for unknown value and handle like {@code submited}.
     * <li> getCluster returns {@code null}: If more than 3 times in a row,
     *      return {@code false}.  Else wait 10 seconds, then poll again.
     * </ul>
     *
     * @param conn The exn connector.
     * @param appID The application id.
     * @param clusterName The name of the cluster to poll.
     */
    private static boolean waitForClusterDeploymentFinished(ExnConnector conn, String appID, String clusterName) {
        final int pollInterval = 10000; // Check status every 10s
        int callsSincePrinting = 0; // number of intervals since we last logged what we're doing
        int failedCalls = 0;
        final int maxFailedCalls = 3; // number of retries if getCluster returns null
        while (true) {
            // Note: values for the "status" field come from SAL source:
            // https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/887b19b1c1f991b517a3983133bd8857e7e9cc2b/sal-service/src/main/java/org/ow2/proactive/sal/service/service/ClusterService.java#L200

            try {
                // Immediately sleep on first loop iteration, so SAL has a chance to catch up
                Thread.sleep(pollInterval);
            } catch (InterruptedException e1) {
                // ignore
            }
            JsonNode clusterState = conn.getCluster(appID, clusterName);
            final String status;
            if (clusterState != null) {
                JsonNode jsonState = clusterState.at("/status");
                status = jsonState.isMissingNode() ? null : jsonState.asText();
            } else {
                status = null;
            }
            if (status == null) {
                failedCalls++;
                if (failedCalls >= maxFailedCalls) {
                    log.warn("getCluster returned invalid result (null or structure without 'status' field) too many times, giving up");
                    return false;
                } else {
                    log.warn("getCluster returned invalid result (null or structure without 'status' field), retrying");
                    continue;
                }
            } else {
                // Forget about intermittent failures
                failedCalls = 0;
            }
            if (status.equals(STATUS_DEPLOYED)) {
                log.info("Cluster deployment finished successfully");
                return true;
            } else if (status.equals(STATUS_FAILED)) {
                log.warn("Cluster deployment failed");
                return false;
            } else {
                if (!status.equals(STATUS_SUBMITTED)
                    && !status.equals(STATUS_SCALING)) {
                    // Better paranoid than sorry
                    log.warn("Unknown 'status' value in getCluster result: {}", status);
                }
                // still waiting, log every minute
                if (callsSincePrinting < 5) {
                    callsSincePrinting++;
                } else {
                    log.info("Waiting for cluster deployment to finish, cluster state = {}", clusterState);
                    callsSincePrinting = 0;
                }
            }
        }
    }

    /**
     * Given a KubeVela file, extract node requirements, create the job, start
     * its nodes and submit KubeVela.
     *
     * Note: this method is not thread-safe and should only be called from
     * {@link NebulousApp#deploy()} or similarly protected code.
     *
     * @param app The NebulOuS app object.
     * @param kubevela the KubeVela file to deploy.
     */
    public static void deployApplication(NebulousApp app, JsonNode kubevela) {
        String appUUID = app.getUUID();
        String clusterName = app.getClusterName();
        if (!app.setStateDeploying()) {
            log.error("Trying to deploy app that is in state {} (should be READY), aborting deployment",
                app.getState().name());
            app.setStateFailed();
            return;
        }
        ExnConnector conn = app.getExnConnector();
        log.info("Starting initial deployment for application");

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
        Map<String, JsonNode> components = new HashMap<>();
        kubevela.withArray("/spec/components").forEach(
            c -> components.put(c.at("/name").asText(), c));
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
        JsonNode rewritten;
        try {
            rewritten = createDeploymentKubevela(kubevela);
        } catch (IllegalStateException e) {
            log.error("Failed to create deployment kubevela", e);
            app.setStateFailed();
            return;
        }
        String rewritten_kubevela = "---\n# Did not manage to create rewritten KubeVela";
        try {
            rewritten_kubevela = yamlMapper.writeValueAsString(rewritten);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert KubeVela to YAML; this should never happen", e);
            app.setStateFailed();
            return;
        }
        Main.logFile("rewritten-kubevela-" + appUUID + ".yaml", rewritten_kubevela);

        // ----------------------------------------
        // Find node candidates
        List<NodeCandidate> controllerCandidates = conn.findNodeCandidatesMultiple(
            requirementsWithLocations(controllerRequirements, app.getUUID(),
                app.getClouds(), ComponentLocationType.CLOUD_ONLY),
            appUUID);
        if (controllerCandidates.isEmpty()) {
            log.error("Could not find node candidates for requirements: {}, aborting deployment",
                controllerRequirements);
            app.setStateFailed();
            return;
        }
        Map<String, List<NodeCandidate>> componentCandidates = new HashMap<>();
        for (Map.Entry<String, List<Requirement>> e : componentRequirements.entrySet()) {
            String nodeName = e.getKey();
            List<Requirement> requirements = e.getValue();
            List<NodeCandidate> candidates = conn.findNodeCandidatesMultiple(
                requirementsWithLocations(requirements, app.getUUID(), app.getClouds(),
                    getComponentLocation(components.get(nodeName))),
                appUUID);
            if (candidates.isEmpty()) {
                log.error("Could not find node candidates for for node {}, requirements: {}, aborting deployment", nodeName, requirements);
                app.setStateFailed();
                return;
            }
            componentCandidates.put(nodeName, candidates);
        }

        // ------------------------------------------------------------
        // Select node candidates

        Map<String, NodeCandidate> nodeEdgeCandidates = new HashMap<>(app.getNodeEdgeCandidates());

        // Controller node
        log.info("Deciding on controller node candidate");
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
            log.error("Empty node candidate list for controller, aborting deployment");
            app.setStateFailed();
            return;
        }

        // Component nodes
        log.info("Collecting component nodes for {}", appUUID);
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
                log.error("Empty node candidate list for component {}, aborting deployment", componentName);
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
                    log.error("No available node candidate for node {} of component {}, aborting deployment", nodeNumber, componentName);
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
            log.error("Internal error: could not convert node labels to string (this should never happen), aborting deployment");
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
        // See https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/env-variables-necessary-for-nebulous-application-deployment-scripts
        environment.put("APPLICATION_ID", appUUID);
        if (Main.getAppBrokerAddress() == null || Main.getAppBrokerAddress().equals("")) {
            log.warn("ActiveMQ broker address for app (APP_ACTIVEMQ_HOST) is not set, optimistically continuing with 'localhost'");
            environment.put("BROKER_ADDRESS", "localhost");
            environment.put("ACTIVEMQ_HOST", "localhost");
        } else {
            environment.put("BROKER_ADDRESS", Main.getAppBrokerAddress());
            environment.put("ACTIVEMQ_HOST", Main.getAppBrokerAddress());
        }
        // Don't warn when those are unset, 5672 is usually the right call
        environment.put("BROKER_PORT", Integer.toString(Main.getAppBrokerPort()));
        environment.put("ACTIVEMQ_PORT", Integer.toString(Main.getAppBrokerPort()));
        if (Main.getOnmIp() == null || Main.getOnmIp().equals("")) {
            log.warn("Overlay Network Manager address (ONM_IP) is not set, continuing without setting ONM_IP for the app");
        } else {
            environment.put("ONM_IP", Main.getOnmIp());
        }
        if (Main.getOnmUrl() == null || Main.getOnmUrl().equals("")) {
            log.warn("Overlay Network Manager address (ONM_URL) is not set, continuing without setting ONM_URL for the app");
        } else {
            environment.put("ONM_URL", Main.getOnmUrl());
        }
        // TODO: consider pre-parsing environment variables from the app
        // message and storing them in the app object instead of reading them
        // from the raw JSON here -- but it's not that important
        for (final JsonNode v : app.getOriginalAppMessage().withArray("/environmentVariables")) {
            if (v.has("name") && v.has("value") && v.get("name").isTextual()) {
                // TODO: figure out what to do with the `"secret":true` field
                environment.put(v.get("name").asText(), v.get("value").asText());
            } else {
                log.warn("Invalid environmentVariables entry: {}", v);
            }
        }
        log.info("Calling defineCluster");
        boolean defineClusterSuccess = conn.defineCluster(appUUID, clusterName, cluster);
        if (!defineClusterSuccess) {
            log.error("Call to defineCluster failed for message body {}, aborting deployment",
                cluster);
            app.setStateFailed();
            return;
        }

        // ------------------------------------------------------------
        // Deploy cluster
        log.info("Calling deployCluster");
        boolean deployClusterSuccess = conn.deployCluster(appUUID, clusterName);
        if (!deployClusterSuccess) {
            log.error("Call to deployCluster failed, trying to delete cluster {} and aborting deployment",
                cluster);
            app.setStateFailed();
            conn.deleteCluster(appUUID, clusterName);
            return;
        }

        if (!waitForClusterDeploymentFinished(conn, appUUID, clusterName)) {
            log.error("Error while waiting for deployCluster to finish, trying to delete cluster {} and aborting deployment",
                cluster);
            app.setStateFailed();
            conn.deleteCluster(appUUID, clusterName);
            return;
        }

        log.info("Cluster deployment finished, continuing with app deployment");

        log.info("Calling labelCluster");
        boolean labelClusterSuccess = conn.labelNodes(appUUID, clusterName, nodeLabels);
        if (!labelClusterSuccess) {
            log.error("Call to deployCluster failed, aborting deployment");
            app.setStateFailed();
            conn.deleteCluster(appUUID, clusterName);
            return;
        }

        // ------------------------------------------------------------
        // Send metrics to Solver
        app.sendMetricList();

        // ------------------------------------------------------------
        // Deploy application

        log.info("Calling deployApplication");
        long proActiveJobID = conn.deployApplication(appUUID, clusterName, app.getName(), rewritten_kubevela);
        log.info("deployApplication returned ProActive Job ID {}", proActiveJobID);
        if (proActiveJobID == 0) {
            // 0 means conversion from long has failed (because of an invalid
            // response), OR a ProActive job id of 0.
            log.error("DeployApplication ProActive job ID = 0, deployApplication has probably failed; aborting deployment.");
            app.setStateFailed();
            conn.deleteCluster(appUUID, clusterName);
            return;
        }
        // ------------------------------------------------------------
        // Update NebulousApp state

        app.setStateDeploymentFinished(componentRequirements, nodeCounts, componentNodeNames, nodeEdgeCandidates, rewritten);
        log.info("App deployment finished.");
    }

    /**
     * Given a KubeVela file, adapt the running application to its
     * specification.<p>
     *
     * The KubeVela file is already rewritten with updated information from
     * the solver when this method is called, so reflects the desired new
     * state of the application cluster.<p>
     *
     * Note: this method is not thread-safe and should only be called from
     * {@link NebulousApp#processSolution(ObjectNode)} or similarly
     * protected code.
     *
     * @param app the NebulOuS app object.
     * @param updatedKubevela the KubeVela file to deploy.
     */
    public static void redeployApplication(NebulousApp app, ObjectNode updatedKubevela) {
        String appUUID = app.getUUID();
        String clusterName = app.getClusterName();
        ExnConnector conn = app.getExnConnector();
        Map<String, JsonNode> components = new HashMap<>();
        updatedKubevela.withArray("/spec/components").forEach(
            c -> components.put(c.at("/name").asText(), c));

        if (!app.setStateRedeploying()) {
            log.warn("Trying to redeploy app that is in state {} (can only redeploy in state RUNNING), aborting",
                app.getState().name());
            return;
        }

        log.info("Starting redeployment generation {}", app.getDeployGeneration());
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

        // ------------------------------------------------------------
        // Rewrite KubeVela
        JsonNode rewritten;
        try {
            rewritten = createDeploymentKubevela(updatedKubevela);
        } catch (IllegalStateException e) {
            log.error("Failed to create deployment kubevela", e);
            app.setStateFailed();
            return;
        }
        String rewritten_kubevela = "---\n# Did not manage to create rewritten KubeVela";
        try {
            rewritten_kubevela = yamlMapper.writeValueAsString(rewritten);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert KubeVela to YAML; this should never happen", e);
            app.setStateFailed();
            return;
        }
        Main.logFile("rewritten-kubevela-" + appUUID + ".yaml", rewritten_kubevela);

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
                    log.info("Node requirements unchanged but need to add {} nodes to component {}", nAdd, componentName);
                    List<NodeCandidate> candidates = conn.findNodeCandidatesMultiple(
                        requirementsWithLocations(newR, app.getUUID(), app.getClouds(),
                            getComponentLocation(components.get(componentName))),
                        appUUID);
                    if (candidates.isEmpty()) {
                        log.error("Could not find node candidates for requirements: {}", newR);
                        continue;
                    }
                    for (int nodeNumber = 1; nodeNumber <= nAdd; nodeNumber++) {
                        String nodeName = createNodeName(clusterName, componentName, app.getDeployGeneration(), nodeNumber);
                        NodeCandidate candidate = candidates.stream()
                            .filter(each -> !nodeEdgeCandidates.values().contains(each))
                            .findFirst()
                            .orElse(null);
                        if (candidate == null) {
                            log.error("No available node candidate for node {} of component {}", nodeNumber, componentName);
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
                    log.info("Node requirements unchanged but need to remove {} nodes from component {}", nRemove, componentName);
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
                    log.info("Node requirements and replica count unchanged, nothing to do for component {}", componentName);
                    allMachineNames = componentNodeNames.get(componentName);
                }
            } else {
                // Node requirements have changed: need to shut down all
                // current machines and start fresh ones
                nodesToRemove.addAll(componentNodeNames.get(componentName));
                allMachineNames = new HashSet<>();
                log.info("Node requirements changed, need to redeploy all nodes of component {}", componentName);
                List<NodeCandidate> candidates = conn.findNodeCandidatesMultiple(
                    requirementsWithLocations(newR, app.getUUID(), app.getClouds(),
                        getComponentLocation(components.get(componentName))),
                    appUUID);
                if (candidates.size() == 0) {
                    log.error("Empty node candidate list for component {}, continuing without creating node", componentName);
                    continue;
                }
                for (int nodeNumber = 1; nodeNumber <= componentReplicaCounts.get(componentName); nodeNumber++) {
                    String nodeName = createNodeName(clusterName, componentName, app.getDeployGeneration(), nodeNumber);
                    NodeCandidate candidate = candidates.stream()
                        .filter(each -> !nodeEdgeCandidates.values().contains(each))
                        .findFirst()
                        .orElse(null);
                    if (candidate == null) {
                        log.error("No available node candidate for node {} of component {}", nodeNumber, componentName);
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

        if (!nodesToRemove.isEmpty() || !nodesToAdd.isEmpty()) {
            if (!nodesToAdd.isEmpty()) {
                log.info("Starting scaleout: {}", nodesToAdd);
                Main.logFile("redeploy-scaleout-" + appUUID + ".json", nodesToAdd.toPrettyString());
                conn.scaleOut(appUUID, clusterName, nodesToAdd);
                waitForClusterDeploymentFinished(conn, appUUID, clusterName);
            } else {
                log.info("No nodes added, skipping scaleout");
            }

            log.info("Labeling nodes: {}", nodeLabels);
            Main.logFile("redeploy-labelNodes-" + appUUID + ".json", nodeLabels.toPrettyString());
            conn.labelNodes(appUUID, clusterName, nodeLabels);

            log.info("Redeploying application: {}", rewritten_kubevela);
            conn.deployApplication(appUUID, clusterName, app.getName(), rewritten_kubevela);

            if (!nodesToRemove.isEmpty()) {
                Main.logFile("redeploy-scalein-" + appUUID + ".json", nodesToRemove);
                log.info("Starting scalein: {}", nodesToRemove);
                conn.scaleIn(appUUID, clusterName, nodesToRemove);
            } else {
                log.info("No nodes removed, skipping scalein");
            }
        } else {
            log.info("Solution did not require nodes to be added or removed, done.");
        }

        app.setStateDeploymentFinished(componentRequirements, componentReplicaCounts,
            componentNodeNames, nodeEdgeCandidates, updatedKubevela);
        log.info("Redeployment finished");
    }

    /**
     * "Undeploy" an application.  This means telling SAL to delete the app's
     * cluster and modify the application's state as if the app creation
     * message had just come in.  After this method finishes, the app object
     * is in state READY and can perform an initial deployment.
     *
     * Note: no effort is being made to check the success of deleting the
     * cluster, since the app could have been not deployed at all,
     * half-deployed, unsuccessfully redeployed, or running successfully.
     *
     * @see #deleteApplication
     */
    public static void undeployApplication(NebulousApp app) {
        ExnConnector conn = app.getExnConnector();
        conn.deleteCluster(app.getUUID(), app.getClusterName());
        app.resetState();
    }
    /**
     * Delete an application.  In addition to undeploying, also deregister the
     * application object.  After this method finishes, the UI can re-send an
     * initial deployment message for an application with the same UUID as the
     * one passed to this method without that message resulting in an error.
     *
     * @see #undeployApplication
     */
    public static void deleteApplication(NebulousApp app) {
        undeployApplication(app);
        app.setStateDeletedAndUnregister();
    }
}
