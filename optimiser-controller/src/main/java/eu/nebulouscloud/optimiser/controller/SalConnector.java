package eu.nebulouscloud.optimiser.controller;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.Requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;


/**
 * A class that wraps communication with SAL (the Scheduling Abstraction Layer
 * of ProActive) over EXN.
 *
 * Documentation of the SAL REST API is here:
 * https://github.com/ow2-proactive/scheduling-abstraction-layer/tree/master/documentation
 */
@Slf4j
public class SalConnector {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Get list of node candidates that fulfil the requirements.
     *
     * See https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/nodecandidates-endpoints.md#71--filter-node-candidates-endpoint
     *
     * @param requirements The list of requirements.
     * @param appID The application ID, if available.
     * @return A list of node candidates, or null in case of error.
     */
    public static List<NodeCandidate> findNodeCandidates(List<Requirement> requirements, String appID) {
        Map<String, Object> msg;
        try {
            msg = Map.of(
                "metaData", Map.of("user", "admin"),
                "body", mapper.writeValueAsString(requirements));
	} catch (JsonProcessingException e) {
            log.error("Could not convert requirements list to JSON string (this should never happen)", e);
            return null;
	}
        Map<String, Object> response = ExnConnector.findNodeCandidates.sendSync(msg, appID, null, false);
        String body = response.get("body").toString(); // body is a string already
	try {
	    return Arrays.asList(mapper.readValue(body, NodeCandidate[].class));
	} catch (JsonProcessingException e) {
            log.error("Error receiving findNodeCandidates result (this should never happen)", e);
            return null;
	}
    }

}
