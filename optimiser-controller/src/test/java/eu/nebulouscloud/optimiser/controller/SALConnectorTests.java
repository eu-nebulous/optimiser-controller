package eu.nebulouscloud.optimiser.controller;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@WireMockTest
public class SALConnectorTests {
    @Test
    void testConnect(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        //wireMock.register(post("/sal/pagateway/connect").willReturn(ok()));
        URI uri = URI.create(wmRuntimeInfo.getHttpBaseUrl());
        stubFor(post(urlPathEqualTo("/sal/pagateway/connect"))
                .atPriority(1)
                .withFormParam("name", equalTo("test"))
                .withFormParam("password", equalTo("testpasswd"))
                .willReturn(ok("session key")));
        stubFor(post(urlPathEqualTo("/sal/pagateway/connect"))
                .atPriority(5)
                .willReturn(unauthorized()));

        SalConnector connector_success = new SalConnector(uri);
        assertTrue(connector_success.connect("test", "testpasswd"));
        SalConnector connector_fail = new SalConnector(uri);
        assertFalse(connector_fail.connect("test", "passwd"));
    }
}
