package eu.nebulous.optimiser.controller;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import static org.junit.jupiter.api.Assertions.*;

public class AppParserTest {

    private File getResourceFile(String name) throws URISyntaxException {
        URL resourceUrl = getClass().getClassLoader().getResource(name);
        return new File(resourceUrl.toURI());
    }

    @Test
    void readKubevelaFile() throws URISyntaxException {
        File file = getResourceFile("vela-deployment.yaml");
        AppParser p = new AppParser();
        assertTrue(p.parseKubevela(file));
    }

    @Test
    void readParamerizedKubevelaFile() throws URISyntaxException {
        File file = getResourceFile("vela-deployment-parameterized.yaml");
        AppParser p = new AppParser();
        assertTrue(p.parseKubevela(file));
    }

    @Test
    void readResourceModel() throws URISyntaxException {
        File file = getResourceFile("surveillance_app_SAMPLE_metric_model.yml");
        AppParser p = new AppParser();
        assertTrue(p.parseKubevela(file));
    }
}
