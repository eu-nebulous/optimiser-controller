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

    public static List<NodeCandidate> findNodeCandidates(List<Requirement> requirements, String appID) {
        Map<String, Object> msg = new HashMap<>();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("user", "admin");
        msg.put("metaData", metadata);
        try {
	    msg.put("body", mapper.writeValueAsString(requirements));
	} catch (JsonProcessingException e) {
            log.error("Could not convert requirements list to JSON string", e);
            return null;
	}
        Map<String, Object> response = ExnConnector.findNodeCandidates.sendSync(msg, appID, null, false);
        String body = response.get("body").toString(); // body is a string already
	try {
	    return Arrays.asList(mapper.readValue(body, NodeCandidate[].class));
	} catch (JsonProcessingException e) {
            log.error("Error receiving findNodeCandidates result", e);
            return null;
	}
    }

}
