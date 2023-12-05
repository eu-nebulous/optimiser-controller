package eu.nebulous.optimiser.controller;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A class that wraps communication with SAL (the Scheduling Abstraction Layer
 * of ProActive) over REST.
 *
 * Documentation of the SAL REST API is here:
 * https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/deployment-manager-sal-1
 */
public class SalConnector {

    private static final Logger log = LoggerFactory.getLogger(SalConnector.class);
    private URI sal_uri;
    private String session_id = null;

    /**
     * Construct a SalConnector instance.
     *
     * @param sal_uri the URI of the SAL server.  Should only contain schema,
     * host, port but no path component, since relative paths will be resolved
     * against this URI.
     */
    public SalConnector(URI sal_uri) {
        this.sal_uri = sal_uri;
    }

    /**
     * Check if we are connected to a SAL endpoint, i.e., we successfully
     * obtained a session id.
     *
     * @return true if we are connected, false if not
     */
    public boolean isConnected() {
        return session_id != null;
    }

    /**
     * Establish a connection with the SAL server.
     *
     * This method needs to be called before any other method, since it
     * obtains the session id.
     *
     * @param sal_username the user name to log in to SAL
     * @param sal_password the password to log in to SAL
     * @return true if the connection was successful, false if not
     */
    public boolean connect(String sal_username, String sal_password) {
        URI endpoint_uri = sal_uri.resolve("sal/pagateway/connect");
        log.info("Connecting to SAL as a service at uri {}", endpoint_uri);

        String formData = "name=" + URLEncoder.encode(sal_username, StandardCharsets.UTF_8)
            + "&" + "password=" + URLEncoder.encode(sal_password, StandardCharsets.UTF_8);
        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(endpoint_uri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formData))
            .build();

        HttpResponse<String> response = null;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            log.error("Could not acquire SAL session id: ", e);
            return false;
        }

        if (response.statusCode() == 200) {
            session_id = response.body();
            log.info("Connected to SAL at {}", sal_uri);
            return true;
        } else {
            log.error("Could not acquire SAL session id, server response status code: " + response.statusCode());
            return false;
        }
    }

}
