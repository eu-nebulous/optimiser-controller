package eu.nebulouscloud.optimiser.controller;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Class that manages a collection of NebulousApp instances.  Also used to
 * temporarily store the relevant performance indicators which are calculated
 * by the utility evaluator from the same incoming app creation message.
 */
@Slf4j
public class NebulousApps {

    /** The global app registry. */
    private static final Map<String, NebulousApp> apps = new ConcurrentHashMap<>();

    /**
     * A place to store relevant performance indicator messages if the app
     * object is not yet available.
     */
    public static final Map<String, JsonNode> relevantPerformanceIndicators = new ConcurrentHashMap<>();

    /**
     * Add a new application object to the registry.
     *
     * @param app a fresh NebulousApp instance.  It is an error if the
     *  registry already contains an app with the same uuid.
     */
    public static synchronized void add(NebulousApp app) {
        String uuid = app.getUUID();
        apps.put(uuid, app);
        log.debug("Added application");
    }

    /**
     * Lookup the application object with the given uuid.
     *
     * @param uuid the app's UUID
     * @return the application object, or null if not found
     */
    public static synchronized NebulousApp get(String uuid) {
        return apps.get(uuid);
    }

    /**
     * Remove the application object with the given uuid.
     *
     * @param uuid the app object's UUID
     * @return the removed app object
     */
    public static synchronized NebulousApp remove(String uuid) {
        NebulousApp app = apps.remove(uuid);
        if (app != null) {
            log.debug("Removed application");
        } else {
            log.error("Trying to remove unknown application");
        }
        return app;
    }

    /**
     * Remove all application objects.
     */
    public static synchronized void clear() {
        apps.clear();
    }

    /**
     * Return all currently registered apps.
     *
     * @return a collection of all apps
     */
    public static synchronized Collection<NebulousApp> values() {
        return apps.values();
    }

    /**
     * Calculate a short, unique cluster name from the given application id.
     * Currently, we use the first 5 characters of the application id followed
     * by the current number of registered applications.  We deem the risk of
     * two applications with identical UUID heads racing to register to be
     * acceptable.
     *
     * @param applicationUuid the ID of an application that is not yet registered.
     * @return a short string that is unique across all registered applications.
     */
    public static synchronized String calculateUniqueClusterName(String applicationUuid) {
        return applicationUuid.substring(0, 5) + "-" + (apps.size() + 1);
    }
}
