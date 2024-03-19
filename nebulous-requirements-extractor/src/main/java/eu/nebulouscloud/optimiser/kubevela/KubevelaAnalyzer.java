package eu.nebulouscloud.optimiser.kubevela;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import lombok.extern.slf4j.Slf4j;

import org.ow2.proactive.sal.model.AttributeRequirement;
import org.ow2.proactive.sal.model.OperatingSystemFamily;
import org.ow2.proactive.sal.model.Requirement;
import org.ow2.proactive.sal.model.RequirementOperator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A collection of methods to extract node requirements from KubeVela files.
 */
@Slf4j
public class KubevelaAnalyzer {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * Given a KubeVela file, extract how many nodes to deploy for each
     * component.  Note that this can be zero when the component should not be
     * deployed at all.  This can happen for example when there is a cloud and
     * an edge version of the component and only one of them should run.<p>
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
     * If this trait is not found for a component, its count will be 1.
     *
     * @param kubevela the parsed KubeVela file.
     * @return A map from component name to number of instances to generate.
     */
    public static Map<String, Integer> getNodeCount(JsonNode kubevela) {
        Map<String, Integer> result = new HashMap<>();
        ArrayNode components = kubevela.withArray("/spec/components");
        for (final JsonNode c : components) {
            result.put(c.get("name").asText(), 1); // default value; might get overwritten
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
     * Extract node count from a KubeVela file.
     *
     * @see #getNodeCount(JsonNode)
     * @param kubevela The KubeVela file, as a YAML string.
     * @return A map from component name to number of instances to generate.
     * @throws JsonProcessingException if the argument does not contain valid YAML.
     */
    public static Map<String, Integer> getNodeCount(String kubevela) throws JsonProcessingException {
        return getNodeCount(parseKubevela(kubevela));
    }

    /**
     * Extract node requirements from a KubeVela file in a form we can send to
     * the SAL `findNodeCandidates` endpoint. <p>
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
     * - We add the requirement that OS family == Ubuntu.<p>
     *
     * - For the first version, we specify all requirements as "greater or
     *   equal", i.e., we might not find precisely the node candidates that
     *   are asked for. <p>
     *
     * - Related, KubeVela specifies "cpu" as a fractional value, while SAL
     *   wants the number of cores as a whole number.  We round up to the
     *   nearest integer and ask for "this or more" cores, since we might end
     *   up with needing, e.g., 3 cores, which is not a configuration commonly
     *   provided by cloud providers. <p>
     *
     * @param kubevela the parsed KubeVela file.
     * @return a map of component name to (potentially empty, except for OS
     *  family) list of requirements for that component.  No requirements mean
     *  any node will suffice.
     */
    public static Map<String, List<Requirement>> getRequirements(JsonNode kubevela) {
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
                    reqs.add(new AttributeRequirement("hardware", "ram",
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
     * Extract node requirements from a KubeVela file.
     *
     * @see #getRequirements(JsonNode)
     * @param kubevela The KubeVela file, as a YAML string.
     * @return a map of component name to (potentially empty, except for OS
     *  family) list of requirements for that component.  No requirements mean
     *  any node will suffice.
     * @throws JsonProcessingException if kubevela does not contain valid YAML.
     */
    public static Map<String, List<Requirement>> getRequirements(String kubevela) throws JsonProcessingException {
        return getRequirements(parseKubevela(kubevela));
    }

    /**
     * Convert YAML KubeVela into a parsed representation.
     *
     * @param kubevela The KubeVela YAML.
     * @return A parsed representation of the KubeVela file, or null for a parse error.
     * @throws JsonProcessingException if kubevela does not contain valid YAML.
     */
    public static JsonNode parseKubevela(String kubevela) throws JsonProcessingException {
        return yamlMapper.readTree(kubevela);
    }

    /**
     * Convert the parsed representation of a KubeVela file to yaml.
     *
     * @param kubevela The KubeVela parsed file.
     * @return A YAML representation of the KubeVela file.
     * @throws JsonProcessingException if YAML cannot be generated from kubevela.
     */
    public static String generateKubevela(JsonNode kubevela) throws JsonProcessingException {
        return yamlMapper.writeValueAsString(kubevela);
    }
}
