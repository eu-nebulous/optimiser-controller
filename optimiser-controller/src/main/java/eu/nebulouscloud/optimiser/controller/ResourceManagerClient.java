package eu.nebulouscloud.optimiser.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for interacting with the Resource Manager REST API to retrieve node information.
 */
@Slf4j
public class ResourceManagerClient {
   

    private static final String STATE_ENDPOINT = "/rest/rm/state/";
    private static final String AUTH_ENDPOINT = "/rest/common/login/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String username;
    private final String password;
    private String sessionId;
    
    /**
     * Creates a new ResourceManagerClient with default URLs and credentials.
     */
    public ResourceManagerClient() {
        this(Main.getProactiveURL(), Main.getProactiveUser(), Main.getProactivePassword());
    }

    /**
     * Creates a new ResourceManagerClient with custom URLs and credentials.
     * 
     * @param baseUrl the base URL of the Resource Manager API
     * @param authUrl the base URL for authentication
     * @param username the username for authentication
     * @param password the password for authentication
     */
    public ResourceManagerClient(String baseUrl,  String username, String password) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Authenticates with the authentication service and retrieves a session ID.
     * 
     * @return the session ID for subsequent requests
     * @throws ResourceManagerException if authentication fails
     */
    public String authenticate() throws ResourceManagerException {
        try {
            log.debug("Authenticating with username: {}", username);
            
            String url = baseUrl + AUTH_ENDPOINT;
            String formData = String.format("username=%s&password=%s", 
                java.net.URLEncoder.encode(username, "UTF-8"),
                java.net.URLEncoder.encode(password, "UTF-8"));
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formData))
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new ResourceManagerException(
                    String.format("Authentication failed with status code: %d, response: %s", 
                                response.statusCode(), response.body())
                );
            }
            
       
            this.sessionId = response.body();
            log.debug("Successfully authenticated, session ID: {}", this.sessionId);
            return this.sessionId;
            
        } catch (IOException e) {
            throw new ResourceManagerException("Failed to send authentication request", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceManagerException("Authentication request was interrupted", e);
        }
    }
   
    
    /**
     * Retrieves the alive nodes from the Resource Manager using a specific session ID.
     * 
     * @param sessionId the session ID for authentication
     * @return a list of alive node URLs
     * @throws ResourceManagerException if the request fails or the response cannot be parsed
     */
    public List<String> getAliveNodesURLs() throws ResourceManagerException {
    	
        try {
        	 if (sessionId == null) {
                 authenticate();
             }
            log.debug("Requesting alive nodes from Resource Manager with session ID: {}", sessionId);
            
            String url = baseUrl + STATE_ENDPOINT;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("sessionid", sessionId)
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(REQUEST_TIMEOUT)
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new ResourceManagerException(
                    String.format("HTTP request failed with status code: %d, response: %s", 
                                response.statusCode(), response.body())
                );
            }
            
            return parseAliveNodesFromResponse(response.body());
            
        } catch (IOException e) {
            throw new ResourceManagerException("Failed to send HTTP request", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResourceManagerException("Request was interrupted", e);
        }
    }
    
     
    /**
     * Parses the alive nodes from the JSON response.
     * 
     * @param jsonResponse the JSON response string
     * @return a list of alive node URLs
     * @throws ResourceManagerException if the JSON cannot be parsed
     */
    private List<String> parseAliveNodesFromResponse(String jsonResponse) throws ResourceManagerException {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode aliveNodesNode = rootNode.get("aliveNodes");
            
            if (aliveNodesNode == null || !aliveNodesNode.isArray()) {
                throw new ResourceManagerException("Response does not contain 'aliveNodes' array");
            }
            
            List<String> aliveNodes = new ArrayList<>();
            for (JsonNode node : aliveNodesNode) {
                if (node.isTextual()) {
                    aliveNodes.add(node.asText());
                }
            }
            
            log.debug("Successfully parsed {} alive nodes from response", aliveNodes.size());
            return aliveNodes;
            
        } catch (Exception e) {
            throw new ResourceManagerException("Failed to parse JSON response", e);
        }
    }
    
    /**
     * Exception thrown when Resource Manager operations fail.
     */
    public static class ResourceManagerException extends Exception {
        public ResourceManagerException(String message) {
            super(message);
        }
        
        public ResourceManagerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
