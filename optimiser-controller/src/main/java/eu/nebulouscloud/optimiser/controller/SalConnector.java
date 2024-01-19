package eu.nebulouscloud.optimiser.controller;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.logging.LogLevel;
import lombok.extern.slf4j.Slf4j;

import org.ow2.proactive.sal.model.IaasDefinition;
import org.ow2.proactive.sal.model.Job;
import org.ow2.proactive.sal.model.JobDefinition;
import org.ow2.proactive.sal.model.NodeCandidate;
import org.ow2.proactive.sal.model.PACloud;
import org.ow2.proactive.sal.model.Requirement;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


/**
 * A class that wraps communication with SAL (the Scheduling Abstraction Layer
 * of ProActive) over REST.
 *
 * Documentation of the SAL REST API is here:
 * https://openproject.nebulouscloud.eu/projects/nebulous-collaboration-hub/wiki/deployment-manager-sal-1
 */
@Slf4j
public class SalConnector {

    private static final String connectStr = "sal/pagateway/connect";
    private static final String getAllCloudsStr = "sal/cloud";
    private static final String findNodeCandidatesStr = "sal/nodecandidates";
    private static final String createJobStr = "sal/job";
    private static final String getJobsStr = "sal/job"; // same, but different method/body
    private static final String addNodesFormatStr = "sal/node/%s";
    private static final String submitJobFormatStr = "sal/job/%s/submit";
    private static final String stopJobsStr = "sal/job/stop";

    private URI sal_uri;
    private final HttpClient httpClient;
    private String session_id = null;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Construct a SalConnector instance.
     *
     * @param sal_uri the URI of the SAL server.  Should only contain schema,
     *  host, port but no path component, since relative paths will be
     *  resolved against this URI.
     * @param login the login name for SAL.
     * @param password the login password for SAL.
     */
    public SalConnector(URI sal_uri, String login, String password) {
        this.sal_uri = sal_uri;
        // This initialization code copied from
        // https://gitlab.ow2.org/melodic/melodic-integration/-/blob/morphemic-rc4.0/connectors/proactive_client/src/main/java/cloud/morphemic/connectors/ProactiveClientConnectorService.java
        objectMapper.configOverride(List.class)
            .setSetterInfo(JsonSetter.Value.forValueNulls(Nulls.AS_EMPTY))
            .setSetterInfo(JsonSetter.Value.forContentNulls(Nulls.AS_EMPTY));
        this.connect(login, password);
        this.httpClient = HttpClient.create()
            .baseUrl(sal_uri.toString())
            .headers(headers -> headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
            .headers(headers -> headers.add("sessionid", session_id))
            .responseTimeout(Duration.of(80, ChronoUnit.SECONDS))
            .wiretap("reactor.netty.http.client.HttpClient", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL, StandardCharsets.UTF_8);
        this.httpClient.warmup().block();
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
    private void connect(String sal_username, String sal_password) {
        URI endpoint_uri = sal_uri.resolve(connectStr);
        log.info("Connecting to SAL as a service at uri {}", endpoint_uri);

        this.session_id = HttpClient.create()
            .headers(headers -> headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED))
                .post()
                .uri(endpoint_uri)
                .sendForm((req, form) -> form
                          .attr("username", sal_username)
                        .attr("password", sal_password))
                .responseContent()
                .aggregate()
                .asString()
                .retry(20)
                .block();
        log.info("Connected to SAL, sessionid {}...", session_id.substring(0, 10));
    }

    /**
     * Get all cloud providers.  See
     * https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/2-cloud-endpoints.md#22--getallclouds-endpoint
     */
    public List<PACloud> getAllClouds() {
        return httpClient.get()
            .uri(sal_uri.resolve(getAllCloudsStr))
            .responseSingle((resp, bytes) -> {
                if (!resp.status().equals(HttpResponseStatus.OK)) {
                    return bytes.asString().flatMap(body -> Mono.error(new RuntimeException(body)));
                } else {
                    return bytes.asString().mapNotNull(s -> {
                            try {
                                return objectMapper.readValue(s, PACloud[].class);
                            } catch (IOException e) {
                                log.error(e.getMessage(), e);;
                                return null;
                            }
                        });
                }
            })
            .doOnError(Throwable::printStackTrace)
            .blockOptional()
            .map(Arrays::asList)
            .orElseGet(Collections::emptyList);
    }

    /**
     * Get node candidates.  See
     * https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/7-node-endpoints.md#71--findnodecandidates-endpoint
     */
    public List<NodeCandidate> findNodeCandidates(List<Requirement> requirements) {
        return httpClient.post()
            .uri(sal_uri.resolve(findNodeCandidatesStr))
                .send(bodyMonoPublisher(requirements))
                .responseSingle((resp, bytes) -> {
                    if (!resp.status().equals(HttpResponseStatus.OK)) {
                    return bytes.asString().flatMap(body -> Mono.error(new RuntimeException(body)));
                    } else {
                        return bytes.asString().mapNotNull(s -> {
                            try {
                                log.info("Received message: {}", s);
                                return objectMapper.readValue(s, NodeCandidate[].class);
                            } catch (IOException e) {
                                log.error(e.getMessage(), e);;
                                return null;
                            }
                        });
                    }
                })
                .doOnError(Throwable::printStackTrace)
                .blockOptional()
                .map(Arrays::asList)
                .orElseGet(Collections::emptyList);
    }

    /**
     * Create job.  See
     * https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/5-job-endpoints.md#51--createjob-endpoint
     */
    public Boolean createJob(JobDefinition job) {
        return httpClient.post()
            .uri(sal_uri.resolve(createJobStr))
                .send(bodyMonoPublisher(job))
                .responseSingle((resp, bytes) -> {
                    if (!resp.status().equals(HttpResponseStatus.OK)) {
                        return bytes.asString().flatMap(body -> Mono.error(new RuntimeException(body)));
                    } else {
                        return bytes.asString().map(Boolean::parseBoolean);
                    }
                })
                .doOnError(Throwable::printStackTrace)
                .block();
    }

/**
 * Get list of jobs.  See
 * https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/5-job-endpoints.md#52--getjobs-endpoint
 */
    public List<Job> fetchJobs() {
        return httpClient.get()
            .uri(sal_uri.resolve(getJobsStr))
            .responseSingle((resp, bytes) -> {
                if (!resp.status().equals(HttpResponseStatus.OK)) {
                    return bytes.asString().flatMap(body -> Mono.error(new RuntimeException(body)));
                } else {
                    return bytes.asString().mapNotNull(s -> {
                            try {
                                return objectMapper.readValue(s, Job[].class);
                            } catch (IOException e) {
                                log.error(e.getMessage(), e);
                                return null;
                            }
                        });
                }
            })
            .doOnError(Throwable::printStackTrace)
            .blockOptional()
            .map(Arrays::asList)
            .orElseGet(Collections::emptyList);
    }


    /**
     * Stop SAL jobs.  See
     * https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/5-job-endpoints.md#54--stopjobs-endpoint
     */
     public Long stopJobs(List<String> jobIds) {
        return httpClient.put()
            .uri(sal_uri.resolve(stopJobsStr))
            .send(bodyMonoPublisher(jobIds))
            .responseSingle((resp, bytes) -> {
                if (!resp.status().equals(HttpResponseStatus.OK)) {
                    return bytes.asString().flatMap(body -> Mono.error(new RuntimeException(body)));
                } else {
                    return bytes.asString().map(Long::parseLong);
                }
            })
            .doOnError(Throwable::printStackTrace)
            .block();
    }

    /**
     * documentation
     */
    public Boolean addNodes(List<IaasDefinition> nodes, String jobId) {
        return httpClient.post()
            .uri(sal_uri.resolve(addNodesFormatStr.formatted(jobId)))
            .send(bodyMonoPublisher(nodes))
            .responseSingle((resp, bytes) -> {
                if (!resp.status().equals(HttpResponseStatus.OK)) {
                    return bytes.asString().flatMap(body -> Mono.error(new RuntimeException(body)));
                } else {
                    // NOTE: was Boolean::new in Morphemic
                    return bytes.asString().map(Boolean::parseBoolean);
                }
            })
            .doOnError(Throwable::printStackTrace)
            .block();
    }

    /**
     * Submit job.  See
     * https://github.com/ow2-proactive/scheduling-abstraction-layer/blob/master/documentation/5-job-endpoints.md#55--submitjob-endpoint
     */
    public String submitJob(String jobId) {
        return httpClient.post()
            .uri(sal_uri.resolve(submitJobFormatStr.formatted(jobId)))
            .responseSingle((resp, bytes) -> {
                if (!resp.status().equals(HttpResponseStatus.OK)) {
                    return bytes.asString().flatMap(body -> Mono.error(new RuntimeException(body)));
                } else {
                    // Note: Morphemic parsed this as a long, but we don't,
                    // since the end point specifies that it returns the
                    // submitted job id or -1
                    return bytes.asString();
                }
            })
            .doOnError(Throwable::printStackTrace)
            .block();
    }

    private Mono<ByteBuf> bodyMonoPublisher(Object body) {
        // if ((body instanceof JSONArray) || (body instanceof JSONObject)) {
        //     return ByteBufMono.fromString(Mono.just(body.toString()));
        // }
        String json = null;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error(e.getMessage(), e);;
        }
        log.info("Sending body json: {}", json);
        return ByteBufMono.fromString(Mono.just(json));
    }

}
