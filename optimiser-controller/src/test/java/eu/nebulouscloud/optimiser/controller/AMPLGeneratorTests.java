package eu.nebulouscloud.optimiser.controller;

import static eu.nebulouscloud.optimiser.controller.NebulousAppTests.getResourcePath;
import static eu.nebulouscloud.optimiser.controller.NebulousAppTests.appFromTestFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class AMPLGeneratorTests {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();

    @Test
    void bug112() throws IOException, URISyntaxException {
        // https://github.com/eu-nebulous/optimiser-controller/issues/112
        NebulousApp app = appFromTestFile("bug-112/app-creation-message.json");
        String ampl_should = Files.readString(getResourcePath("bug-112/fire.ampl"), StandardCharsets.UTF_8);
        JsonNode solverMessage = app.calculateAMPLMessage();
        assertFalse(solverMessage.at("/ModelFileContent").isMissingNode(), "Missing AMPL file in result");
        String ampl_is = solverMessage.at("/ModelFileContent").asText();
        com.google.common.truth.Truth.assertThat(ampl_is).isEqualTo(ampl_should);
    }

    @Test
    void bug42() throws IOException, URISyntaxException {
        // https://github.com/eu-nebulous/optimiser-controller/issues/42
        NebulousApp app = appFromTestFile("bug-42-app-creation-message.json");
        JsonNode solverMessage = app.calculateAMPLMessage();
        assertEquals(solverMessage.at("/Constants/deployed_memory/Value").asLong(),  8192L);
    }

    @Test
    void compareAMPL() throws IOException, URISyntaxException {
        // https://github.com/eu-nebulous/optimiser-controller/issues/80
        NebulousApp app = NebulousAppTests.appFromTestFile("ampl/app-creation-message-dyemac.json");
        String ampl_should = Files.readString(getResourcePath("ampl/dyemac-generated.ampl"),
            StandardCharsets.UTF_8);
        JsonNode solverMessage = app.calculateAMPLMessage();
        assertFalse(solverMessage.at("/ModelFileContent").isMissingNode(), "Missing AMPL file in result");
        String ampl_is = solverMessage.at("/ModelFileContent").asText();
        com.google.common.truth.Truth.assertThat(ampl_is).isEqualTo(ampl_should);
    }

    @Test
    void normalizeSloViolations() throws JsonMappingException, JsonProcessingException {
        // 
        String slo_str = """
          {
            "nodeKey": "eb5158c5-f247-4f2f-8e5c-5e5cb9a3a24f",
            "isComposite": true,
            "condition": "OR",
            "not": false,
            "children": [
              {
                "nodeKey": "548728c8-5bcc-459e-8187-103856cf357a",
                "isComposite": false,
                "metricName": "mean_cpu_consumption_all",
                "operator": "<=",
                "value": -1000000000
              },
              {
                "nodeKey": "e2cb7ac9-4e18-43b3-8317-667dd3d00077",
                "isComposite": false,
                "metricName": "mean_cpu_consumption_all",
                "operator": ">",
                "value": 70
              },
              {
                "nodeKey": "c09c046f-8aa6-4b8b-9a1b-2166e43a2335",
                "isComposite": false,
                "metricName": "mean_requests_per_second",
                "operator": ">",
                "value": 8
              },
              {
                "nodeKey": "dddfab05-4a13-42b6-9902-019672f7266c",
                "isComposite": false,
                "metricName": "mean_requests_per_second",
                "operator": "<=",
                "value": -1000000000
              }
            ]
          }
          """;
        String expected_str = """
            {
                "nodeKey": "eb5158c5-f247-4f2f-8e5c-5e5cb9a3a24f",
                "isComposite": true,
                "condition": "and",
                "not": false,
                "children": [
                    {
                        "nodeKey": "548728c8-5bcc-459e-8187-103856cf357a",
                        "isComposite": false,
                        "metricName": "mean_cpu_consumption_all",
                        "operator": ">=",
                        "value": -1000000000
                    },
                    {
                        "nodeKey": "e2cb7ac9-4e18-43b3-8317-667dd3d00077",
                        "isComposite": false,
                        "metricName": "mean_cpu_consumption_all",
                        "operator": "<=",
                        "value": 70
                    },
                    {
                        "nodeKey": "c09c046f-8aa6-4b8b-9a1b-2166e43a2335",
                        "isComposite": false,
                        "metricName": "mean_requests_per_second",
                        "operator": "<=",
                        "value": 8
                    },
                    {
                        "nodeKey": "dddfab05-4a13-42b6-9902-019672f7266c",
                        "isComposite": false,
                        "metricName": "mean_requests_per_second",
                        "operator": ">=",
                        "value": -1000000000
                    }
                ]
            }
            """;
        ObjectNode slo = (ObjectNode)(mapper.readTree(slo_str));
        ObjectNode normalized = AMPLGenerator.normalizeSLO((ObjectNode)slo);
        AMPLGenerator.negateCondition(normalized);
        JsonNode expected = mapper.readTree(expected_str);
        com.google.common.truth.Truth
            .assertThat(normalized.toPrettyString())
            .isEqualTo(expected.toPrettyString());
        com.google.common.truth.Truth
            .assertWithMessage("normalizeSLO mutated its input")
            .that(slo.toPrettyString())
            .isNotEqualTo(normalized.toPrettyString());
    }

    @Test
    private void testUtilityFunctionSelection() throws IOException, URISyntaxException {
        NebulousApp app_selected = NebulousAppTests.appFromTestFile("objective-selection/app-message-with-selected.json");
        NebulousApp app_unselected = NebulousAppTests.appFromTestFile("objective-selection/app-message-with-unselected.json");
        NebulousApp app_without = NebulousAppTests.appFromTestFile("objective-selection/app-message-without.json");
        assertEquals(app_selected.calculateAMPLMessage().at("/ObjectiveFunction").asText(), "b");
        assertEquals(app_unselected.calculateAMPLMessage().at("/ObjectiveFunction").asText(), "a");
        assertEquals(app_without.calculateAMPLMessage().at("/ObjectiveFunction").asText(), "");
    }
}
