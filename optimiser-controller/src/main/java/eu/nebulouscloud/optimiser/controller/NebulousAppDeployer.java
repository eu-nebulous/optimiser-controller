package eu.nebulouscloud.optimiser.controller;

import java.util.ArrayList;
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
import org.ow2.proactive.sal.model.OperatingSystemFamily;
import org.ow2.proactive.sal.model.Requirement;
import org.ow2.proactive.sal.model.RequirementOperator;
import org.ow2.proactive.sal.model.TaskDefinition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import lombok.extern.slf4j.Slf4j;

/**
 * Deploy or redeploy a NebulousApp.  This class could live as a couple of
 * methods in {@link NebulousApp} but we group all things SAL and deployment
 * in this file for better readability.
 */
@Slf4j
public class NebulousAppDeployer {

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
                        result.put(c.get("name").asText(), t.at("/properties/replicas").asInt());
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
     * - We should use `traits.*.properties.replicas` if `traits.*.type` ==
     *   "scaler" to create multiple instances -- but that's propably a
     *   separate method
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
            ArrayList<Requirement> reqs = new ArrayList<>();
            reqs.add(new AttributeRequirement("image", "operatingSystem.family",
                RequirementOperator.IN, OperatingSystemFamily.UBUNTU.toString()));
            JsonNode cpu = c.at("/properties/cpu");
            if (cpu.isMissingNode()) cpu = c.at("/properties/resources/requests/cpu");
            if (!cpu.isMissingNode() && cpu.isNumber()) {
                // KubeVela has fractional core /cpu requirements
                double kubevela_cpu = Double.parseDouble(cpu.asText());
                long sal_cores = Math.round(Math.ceil(kubevela_cpu));
                if (sal_cores > 0) {
                    reqs.add(new AttributeRequirement("hardware", "cores",
                        RequirementOperator.GEQ, Long.toString(sal_cores)));
                } else {
                    // floatValue returns 0.0 if node is not numeric
                    log.warn("CPU of component {} is 0 or not a number", c.get("name").asText());
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
                        c.get("name").asText(),
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
            result.put(c.get("name").asText(), reqs);
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
    public static void startApplication(JsonNode kubevela, String appUUID, String appName) {
        log.info("Starting application {} with KubeVela", appUUID);
        if (NebulousApp.getSalConnector() == null) {
            log.warn("Tried to submit job, but do not have a connection to SAL");
            return;
        }
        // The overall flow:
        // 1. Create a SAL job, with the uuid and name of the NebulOuS app
        // 2. Extract node requirements from the KubeVela definition
        // 3. Create a coordinator node; this will run the Kubernetes
        //    controller.  This node is in addition to the nodes required by
        //    KubeVela.
        // 4. Submit the job, thereby starting the coordinator node
        // 5. Extract information (IP address, ...) from the coordinator node
        // 6. Add the worker nodes, as specified by KubeVela, to the job
        // 7. Rewrite the KubeVela file to add node affinities, etc.
        // 8. Send the KubeVela file to the coordinator node

        // ------------------------------------------------------------
        // 1. Create SAL job
        log.debug("Creating job info");
        JobInformation jobinfo = new JobInformation(appUUID, appName);
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
        Boolean success = NebulousApp.getSalConnector().createJob(job);
        if (!success) {
            // This can happen if the job has already been submitted
            log.error("Error trying to create the job; SAL createJob returned {}", success);
            log.debug("Check if a job with id {} already exists, run stopJobs if yes", appUUID);
            return;
        }

        // ------------------------------------------------------------
        // 2. Extract node requirements
        Map<String, List<Requirement>> requirements = getSalRequirementsFromKubevela(kubevela);

        // ------------------------------------------------------------
        // 3. Create coordinator node
        log.debug("Creating app coordinator node");
        List<NodeCandidate> controller_candidates
            = NebulousApp.getSalConnector().findNodeCandidates(NebulousApp.getControllerRequirements(appUUID));
        if (controller_candidates.isEmpty()) {
            log.error("Could not find node candidates for controller node; requirements: {}",
                NebulousApp.getControllerRequirements(appUUID));
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
        log.debug("Starting job");
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
        log.debug("Starting worker nodes");
        for (Map.Entry<String, List<Requirement>> e : requirements.entrySet()) {
            List<NodeCandidate> candidates = NebulousApp.getSalConnector().findNodeCandidates(e.getValue());
            if (candidates.isEmpty()) {
                log.error("Could not find node candidates for requirements: {}", e.getValue());
                return;
            }
            NodeCandidate candidate = candidates.get(0);
            IaasDefinition def = new IaasDefinition(
                e.getKey(), "nebulous-worker", candidate.getId(), candidate.getCloud().getId()
            );
            // TODO: can we collect all nodes app-wide and submit them at once?
            success = NebulousApp.getSalConnector().addNodes(List.of(def), appUUID);
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
