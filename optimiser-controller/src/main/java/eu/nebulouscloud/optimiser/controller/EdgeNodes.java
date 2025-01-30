package eu.nebulouscloud.optimiser.controller;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.ow2.proactive.sal.model.NodeCandidate;

import lombok.extern.slf4j.Slf4j;

/**
 * A class that keeps track of edge node assignments.  Since apps can be
 * deployed in parallel, someone needs to make sure that no edge node is
 * assigned to two apps at the same time.
 */
@Slf4j
public class EdgeNodes {

    private EdgeNodes() { }

    /** Mapping from (edge) node candidate to application ID */
    private static final Map<NodeCandidate, String> edgeAssignments = new ConcurrentHashMap<>();

    /**
     * Try to acquire multiple node candidates for the application.  It is
     * guaranteed that no other app can acquire the edge nodes contained in
     * the return value.  In turn, the app must not use any edge node that was
     * not acquired.
     *
     * <p>Node candidates in the wishlist that are already owned by the same
     * app are included in the result.
     *
     * @param appID the appplication id.
     * @param wishlist the edge node candidates to claim.
     * @return the edge node candidates that were successfully claimed.
     */
    public static synchronized Set<NodeCandidate> acquire(String appID, Collection<NodeCandidate> wishlist) {
        Set<NodeCandidate> result = new HashSet<>();
        for (NodeCandidate node : wishlist) {
            if (edgeAssignments.getOrDefault(node, "").equals(appID)) {
                result.add(node);
            } else if (!edgeAssignments.containsKey(node)) {
                edgeAssignments.put(node, appID);
                result.add(node);
            } else {
                // claimed by someone else; skip
            }
        }
        log.info("Asked to acquire edge nodes with ids {}, successfully acquired {}",
            wishlist.stream().map(node -> node.getId()).collect(Collectors.joining(",")),
            result.stream().map(node -> node.getId()).collect(Collectors.joining(",")));
        return result;
    }

    /**
     * Try to acquire the given node candidate for the application.  It is
     * guaranteed that no other app can acquire that edge node after this
     * method returns {@code true}.  In turn, the app must not use any edge
     * node that was not acquired.
     *
     * <p>If the node candidate is already acquired by the same app, the
     * result is {@code true}.
     *
     * @param appID the appplication id.
     * @param candidate the node candidate.
     * @return true if the candidate is acquired by the app, false otherwise.
     */
    public static synchronized boolean acquire(String appID, NodeCandidate candidate) {
        if (edgeAssignments.getOrDefault(candidate, "").equals(appID)) {
            log.info("Re-acquired already owned edge node {}", candidate.getId());
            return true;
        } else if (!edgeAssignments.containsKey(candidate)) {
            log.info("Acquired edge node {}", candidate.getId());
            edgeAssignments.put(candidate, appID);
            return true;
        } else {
            log.info("Could not acquire edge node {} (already owned by app with id {})",
                candidate.getId(), edgeAssignments.get(candidate));
            return false;
        }
    }


    /**
     * Release the given node candidates.  This can happen during redeployment
     * or application shutdown.  After this method, the nodes contained in
     * {@code ownedNodes} can be acquired again.
     *
     * <p>Node candidates in {@code ownedNodes} that are not owned by the
     * application (free or owned by another application) do not change their
     * status.
     *
     * @param appID the application id.
     * @param ownedNodes edge node candidates that should no longer be claimed
     *  by {@code appID}.  It is harmless for this to include non-edge nodes.
     */
    public static synchronized void release(String appID, Collection<NodeCandidate> ownedNodes) {
        if (ownedNodes.isEmpty()) return;
        Set<NodeCandidate> removedNodes = new HashSet<>();
        for (NodeCandidate node : ownedNodes) {
            if (edgeAssignments.getOrDefault(node, "").equals(appID)) {
                edgeAssignments.remove(node);
                removedNodes.add(node);
            }
        }
        if (!removedNodes.isEmpty()) {
            log.info("Released edge nodes with IDs: {}",
                removedNodes.stream().map(node -> node.getId()).collect(Collectors.joining(",")));
        }
    }

    /**
     * Return all successfully claimed node candidates.
     *
     * @param appID the application id.
     * @return all edge nodes successfully claimed by the application.
     */
    public static synchronized Set<NodeCandidate> ownedEdgeNodes(String appID) {
        return edgeAssignments.entrySet().stream()
            .filter(entry -> entry.getValue().equals(appID))
            .map(entry -> entry.getKey())
            .collect(Collectors.toSet());
    }
}
