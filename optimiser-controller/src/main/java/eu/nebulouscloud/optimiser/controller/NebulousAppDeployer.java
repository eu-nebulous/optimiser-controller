package eu.nebulouscloud.optimiser.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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

    // TODO: find out the commands to initialize the workers
    /**
     * The installation scripts to send to SAL for a NebulOuS worker node.
     */
    @Getter
    private static CommandsInstallation nodeInstallation = new CommandsInstallation();

    /**
     * The requirements of the node running the NebulOuS controller.  This
     * machine runs the Kubernetes cluster and KubeVela.
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
            new AttributeRequirement("hardware", "memory", RequirementOperator.GEQ, "2048"),
            new AttributeRequirement("hardware", "cpu", RequirementOperator.GEQ, "2"));
    }

    /**
     * Given a KubeVela file, extract how many nodes to deploy for each
     * component.
     *
     * We currently detect replica count with the following component trait:
     * ---
     * traits:
     *  - type: scaler
     *    properties:
     *      replicas: 2
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
                        // Note this can be 0, in case we want to balance
                        // between e.g. cloud and edge
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
    public static Map<String, List<Requirement>> getSalRequirementsFromKubevela(JsonNode kubevela) {
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
            for (final JsonNode t : c.withArray("traits")) {
                // Check for node affinity / geoLocation / country
            }
            // Finally, add requirements for this job to the map
            result.put(componentName, reqs);
        }
        return result;
    }

    /**
     * Add affinities trait to all components.
     *
     * TODO: we need to find out which key to use to get the node labels, as
     * assigned by the SAL `addNodes` endpoint.
     *
     * #+begin_src yaml
     * traits:
     *   - type: affinity
     *     properties:
     *       nodeAffinity:
     *         required:
     *           nodeSelectorTerms:
     *           - matchExpressions:
     *             - key: label
     *               operator: In
     *               values: ["machinelabel"]
     * #+end_src
     *
     * @param kubevela the KubeVela specification to modify. This parameter is
     *  not modified.
     * @return a fresh KubeVela specification with added nodeAffinity traits.
     */
    public static JsonNode addNodeAffinities(JsonNode kubevela) {
        JsonNode result = kubevela.deepCopy();
        for (final JsonNode c : result.withArray("/spec/components")) {
            ArrayNode traits = c.withArray("traits");
            ObjectNode trait = traits.addObject();
            trait.put("type", "affinity");
            ArrayNode nodeSelectorTerms = trait.withArray("/properties/nodeAffinity/required/nodeSelectorTerms");
            ArrayNode matchExpressions = nodeSelectorTerms.addObject().withArray("matchExpressions");
            ObjectNode term = matchExpressions.addObject();
            term.put("key", "label")
                .put("operator", "In");
            term.withArray("values").add(c.get("name").asText());
        }
        return result;
    }

    /**
     * Given a KubeVela file, extract node requirements, create the job, start
     * its nodes and submit KubeVela.
     *
     * @param kubevela the KubeVela file to deploy.
     * @param appUUID the application UUID.
     * @param appName the application name.
     */
    public static void deployApplication(JsonNode kubevela, String appUUID, String appName) {
        log.info("Starting initial deployment of {}", appUUID);
        if (NebulousApp.getSalConnector() == null) {
            log.warn("Tried to submit job, but do not have a connection to SAL");
            return;
        }
        // The overall flow:
        //
        // 1. Extract node requirements and node counts from the KubeVela
        //    definition.
        // 2. Create a SAL job, with the uuid and name of the NebulOuS app
        // 3. Create a coordinator node with hardcoded requirements; this node
        //    will run the Kubernetes controller.  This node is in addition to
        //    the nodes required by KubeVela.
        // 4. Submit the job, thereby starting the coordinator node
        // 5. Extract information (IP address, ...) from the coordinator node
        // 6. Add the worker nodes to the job
        // 7. Rewrite the KubeVela file to add node affinities to each
        //    component
        // 8. Send the KubeVela file to the coordinator node

        // ------------------------------------------------------------
        // 1. Extract node requirements
        Map<String, List<Requirement>> requirements = getSalRequirementsFromKubevela(kubevela);
        Map<String, Integer> nodeCounts = getNodeCountFromKubevela(kubevela);

        // ------------------------------------------------------------
        // 2. Create SAL job
        log.debug("Creating job info for {}", appUUID);
        JobInformation jobinfo = new JobInformation(appUUID, appName);
        // TODO: figure out what ports to specify here
        List<Communication> communications = List.of();
        // This task is deployed on the controller node (the one not specified
        // in the app KubeVela file)
        TaskDefinition nebulous_controller_task = new TaskDefinition(
            "nebulous-controller", controllerInstallation, List.of());
        // This task is deployed on all worker nodes (the ones specified by
        // the app KubeVela file and optimized by NebulOuS)
        // TODO: find out if/how to modify `nebulous_worker_task` to pass in
        //       information about the controller
        TaskDefinition nebulous_worker_task = new TaskDefinition(
            "nebulous-worker", nodeInstallation, List.of());
        List<TaskDefinition> tasks = List.of(nebulous_controller_task, nebulous_worker_task);
        JobDefinition job = new JobDefinition(communications, jobinfo, tasks);
        Boolean success = NebulousApp.getSalConnector().createJob(job);
        if (!success) {
            // This can happen if the job has already been submitted
            log.error("Error trying to create the job; SAL createJob returned {}", success);
            log.debug("Check if a job with id {} already exists, run stopJobs if yes", appUUID);
            return;
        }

        // ------------------------------------------------------------
        // 3. Create coordinator node
        log.debug("Creating app coordinator node for {}", appUUID);
        List<NodeCandidate> controller_candidates
            = NebulousApp.getSalConnector().findNodeCandidates(getControllerRequirements(appUUID));
        if (controller_candidates.isEmpty()) {
            log.error("Could not find node candidates for controller node; requirements: {}",
                getControllerRequirements(appUUID));
            return;
        }
        NodeCandidate controller_candidate = controller_candidates.get(0);

        IaasDefinition controller_def = new IaasDefinition(
            "nebulous-controller-node", "nebulous-controller",
            controller_candidate.getId(), controller_candidate.getCloud().getId());
        success = NebulousApp.getSalConnector().addNodes(List.of(controller_def), appUUID);
        if (!success) {
            log.error("Failed to add controller node: {}", controller_candidate);
            return;
        }

        // ------------------------------------------------------------
        // 4. Submit job
        log.debug("Starting job {}", appUUID);
        String return_job_id = NebulousApp.getSalConnector().submitJob(appUUID);
        if (return_job_id.equals("-1")) {
            log.error("Failed to add start job {}, SAL returned {}",
                appUUID, return_job_id);
            return;
        }

        // ------------------------------------------------------------
        // 5. Extract coordinator node information

        // TODO

        // ------------------------------------------------------------
        // 6. Create worker nodes from requirements
        log.debug("Starting worker nodes for {}", appUUID);
        for (Map.Entry<String, List<Requirement>> e : requirements.entrySet()) {
            List<NodeCandidate> candidates = NebulousApp.getSalConnector().findNodeCandidates(e.getValue());
            if (candidates.isEmpty()) {
                log.error("Could not find node candidates for requirements: {}", e.getValue());
                return;
            }
            NodeCandidate candidate = candidates.get(0);
            // Here we specify the node names that we (hope to) use for node
            // affinity declarations in KubeVela
            IaasDefinition def = new IaasDefinition(
                e.getKey(), "nebulous-worker", candidate.getId(), candidate.getCloud().getId()
            );
            int n = nodeCounts.get(e.getKey());
            log.debug("Asking for {} copies of {} for application {}", n, candidate, appUUID);
            success = NebulousApp.getSalConnector().addNodes(Collections.nCopies(n, def), appUUID);
            if (!success) {
                log.error("Failed to add node: {}", candidate);
            }
        }

        // ------------------------------------------------------------
        // 7. Rewrite KubeVela file, based on running node names

        // TODO
        JsonNode rewritten = addNodeAffinities(kubevela);

        // ------------------------------------------------------------
        // 8. Submit KubeVela file to coordinator node

        // TODO
    }

    /**
     * Redeploy a running application.
     */
    public static void redeployApplication(NebulousApp app, ObjectNode kubevela) {
        // The overall flow:
        // 
        // 1. Extract node requirements and node counts from the updated
        //    KubeVela definition.
        // 2. Extract current nodes from running SAL job
        // 3. Calculate new (to be started) and superfluous (to be shutdown)
        //    nodes
        // 4. Find node candidates for new nodes (from Step 3) according to
        //    their requirements (from Step 1)
        // 5. Create nodes, add them to SAL job
        // 6. Rewrite KubeVela with updated node affinities
        // 7. Send updated KubeVela to running cluster
        // 8. Shut down superfluous nodes (from Step 3)
        
    }

}
