package eu.nebulouscloud.optimiser.controller;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.ow2.proactive.sal.model.PACloud;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;


/**
 * A class that wraps communication with SAL (the Scheduling Abstraction Layer
 * of ProActive) over REST.
 *
 * Documentation of the SAL REST API is here:
 * https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/deployment-manager-sal-1
 */
public class SalConnector {

    private static final Logger log = LoggerFactory.getLogger(SalConnector.class);

    private static final String connectStr = "sal/pagateway/connect";
    private static final String getAllCloudsStr = "sal/cloud";

    private URI sal_uri;
    private String session_id = null;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // "Once built, an HttpClient is immutable, and can be used to send
    // multiple requests."
    // (https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.html)
    // -- so we create an instance variable.
    private HttpClient client = HttpClient.newBuilder().build();

    /**
     * Construct a SalConnector instance.
     *
     * @param sal_uri the URI of the SAL server.  Should only contain schema,
     * host, port but no path component, since relative paths will be resolved
     * against this URI.
     */
    public SalConnector(URI sal_uri) {
        this.sal_uri = sal_uri;
        // This initialization code copied from
        // https://gitlab.ow2.org/melodic/melodic-integration/-/blob/morphemic-rc4.0/connectors/proactive_client/src/main/java/cloud/morphemic/connectors/ProactiveClientConnectorService.java
        objectMapper.configOverride(List.class)
            .setSetterInfo(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY))
            .setSetterInfo(JsonSetter.Value.forContentNulls(Nulls.AS_EMPTY));
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
        URI endpoint_uri = sal_uri.resolve(connectStr);
        log.info("Connecting to SAL as a service at uri {}", endpoint_uri);

        String formData = "name=" + URLEncoder.encode(sal_username, StandardCharsets.UTF_8)
            + "&" + "password=" + URLEncoder.encode(sal_password, StandardCharsets.UTF_8);
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

    public List<PACloud> getAllClouds() {
        URI endpoint_uri = sal_uri.resolve(getAllCloudsStr);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(endpoint_uri)
            .header("sessionid", session_id)
            .GET()
            .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String b = response.body();
                return Arrays.asList(objectMapper.readValue(b, PACloud[].class));
            } else {
                log.error("Request {} failed, server response status code: {}",
                    endpoint_uri, response.statusCode());
                return null;
            }
        } catch (IOException | InterruptedException e) {
            log.error("Request " + endpoint_uri + "failed: ", e);
            return null;
        }
    }

}
