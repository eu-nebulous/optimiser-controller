package eu.nebulouscloud.optimiser.controller;

import lombok.extern.slf4j.Slf4j;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that manages a collection of NebulousApp instances.
 */
@Slf4j
public class NebulousApps {
    
    /** The global app registry. */
    // (Putting this here until we find a better place.)
    private static final Map<String, NebulousApp> apps = new ConcurrentHashMap<String, NebulousApp>();

    /**
     * Add a new application object to the registry.
     *
     * @param app a fresh NebulousApp instance.  It is an error if the
     *  registry already contains an app with the same uuid.
     */
    public static synchronized void add(NebulousApp app) {
        String uuid = app.getUUID();
        apps.put(uuid, app);
        log.debug("Added application", keyValue("appId", uuid));
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
            log.debug("Removed application", keyValue("appId", uuid));
        } else {
            log.error("Trying to remove unknown application", keyValue("appId", uuid));
        }
        return app;
    }

    /**
     * Return all currently registered apps.
     *
     * @return a collection of all apps
     */
    public static synchronized Collection<NebulousApp> values() {
        return apps.values();
    }
}
