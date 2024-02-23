package eu.nebulouscloud.optimiser.controller;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.Requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;
import static net.logstash.logback.argument.StructuredArguments.keyValue;


/**
 * A class that wraps communication with SAL (the Scheduling Abstraction Layer
 * of ProActive) over EXN.
 *
 * Documentation of the SAL REST API is here:
 * https://github.com/ow2-proactive/scheduling-abstraction-layer/tree/master/documentation
 */
@Slf4j
public class SalConnector {

    private SalConnector() {}

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Get list of node candidates from the resource broker that fulfil the
       given requirements.
     *
     * <p>Note that we cannot convert the result to a list containing {@code
     * org.ow2.proactive.sal.model.NodeCandidate} instances, since the broker
     * adds the additional fields {@code score} and {@code ranking}.  Instead
     * we return a JSON {@code ArrayNode} containing {@code ObjectNode}s in
     * the format specified at
     * https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/nodecandidates-endpoints.md#71--filter-node-candidates-endpoint
     * but with these two additional attributes.
     *
     * @param requirements The list of requirements.
     * @param appID The application ID.
     * @return A JSON array containing node candidates.
     */
    public static ArrayNode findNodeCandidates(List<Requirement> requirements, String appID) {
        Map<String, Object> msg;
        try {
            msg = Map.of(
                "metaData", Map.of("user", "admin"),
                "body", mapper.writeValueAsString(requirements));
	} catch (JsonProcessingException e) {
            log.error("Could not convert requirements list to JSON string (this should never happen)",
                keyValue("appId", appID), e);
            return null;
	}
        Map<String, Object> response = ExnConnector.findBrokerNodeCandidates.sendSync(msg, appID, null, false);
        ObjectNode jsonBody = mapper.convertValue(response, ObjectNode.class);
        // Note: what we would really like to do here is something like:
        //
        //     return Arrays.asList(mapper.readValue(response, NodeCandidate[].class));
        //
        // But since the broker adds two attributes, the array elements cannot
        // be deserialized into org.ow2.proactive.sal.model.NodeCandidate
        // objects.
        return jsonBody.withArray("/nodes");
    }

}
