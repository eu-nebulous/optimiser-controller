package eu.nebulouscloud.optimiser.controller;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Generate AMPL from an app message.  This class could live as a couple of
 * methods in {@link NebulousApp} but we group all things AMPL in this file
 * for better readability.
 */
public class AMPLGenerator {
    /**
     * Generate AMPL code for the app, based on the parameter definition(s).
     * Public for testability, not because we'll be calling it outside of its
     * class.
     */
    public static String generateAMPL(NebulousApp app) {
        final StringWriter result = new StringWriter();
        final PrintWriter out = new PrintWriter(result);
        out.format("# AMPL file for application '%s' with id %s%n", app.getName(), app.getUUID());
        out.println();

        out.println("# Variables");
        for (final JsonNode p : app.getKubevelaVariables()) {
            ObjectNode param = (ObjectNode) p;
            String param_name = param.get("key").textValue();
            String param_path = param.get("path").textValue();
            String param_type = param.get("type").textValue();
            ObjectNode value = (ObjectNode)param.get("value");
            if (param_type.equals("float")) {
                out.format("var %s", param_name);
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    // `isNumber` because the constraint might be given as integer
                    if (lower.isNumber()) {
                        out.format(" >= %s", lower.doubleValue());
                        separator = ", ";
                    }
                    if (upper.isNumber()) {
                        out.format("%s<= %s", separator, upper.doubleValue());
                    }
                }
                out.format(";	# %s%n", param_path);
            } else if (param_type.equals("int")) {
                out.format("var %s integer", param_name);
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.get("lower_bound");
                    JsonNode upper = value.get("upper_bound");
                    if (lower.isIntegralNumber()) {
                        out.format(" >= %s", lower.longValue());
                        separator = ", ";
                    }
                    if (upper.isIntegralNumber()) {
                        out.format("%s<= %s", separator, upper.longValue());
                    }
                }
                out.format(";	# %s%n", param_path);
            } else if (param_type.equals("string")) {
                out.println("# TODO not sure how to specify a string variable");
                out.format("var %s symbolic;	# %s%n", param_name, param_path);
            } else if (param_type.equals("array")) {
                out.format("# TODO generate entries for map '%s' at %s%n", param_name, param_path);
            }
        }
        out.println();

        out.println("# Raw metrics");
        out.println("# TODO: here we should also have initial values!");
        for (final JsonNode m : app.getRawMetrics().values()) {
            out.format("param %s;	# %s%n", m.get("key").asText(), m.get("name").asText());
        }
        out.println();

        out.println("# Composite metrics");
        out.println("# TODO: here we should also have initial values!");
        for (final JsonNode m : app.getCompositeMetrics().values()) {
            out.format("param %s;	# %s%n", m.get("key").asText(), m.get("name").asText());
        }
        out.println();

        out.println("# Performance indicators = composite metrics that have at least one variable in their formula");
        for (final JsonNode m : app.getPerformanceIndicators().values()) {
            String formula = replaceVariables(m.get("formula").asText(), m.withObject("mapping"));
            out.format("# %s : %s%n", m.get("name").asText(), m.get("formula").asText());
            out.format("param %s = %s;%n", m.get("key").asText(), formula);
        }
        out.println();

        out.println("# TBD: cost parameters - for all components! and use of node-candidates tensor");
        out.println();

        out.println("# Utility functions");
        for (JsonNode f : app.getOriginalAppMessage().withArray(NebulousApp.utility_function_path)) {
            String formula = replaceVariables(f.get("formula").asText(), f.withObject("mapping"));
            out.format("# %s : %s%n", f.get("name").asText(), f.get("formula").asText());
            out.format("%s %s :%n	%s;%n",
                f.get("type").asText(), f.get("key").asText(),
                formula);
        }
        out.println();

        out.println("# Default utility function: tbd");
        out.println();
        out.println("# Constraints. For constraints we don't have name from GUI, must be created");
        out.println("# TODO: generate from 'slo' hierarchical entry");
        return result.toString();
    }

    /**
     * Replace variables in formulas.
     *
     * @param formula a string like "A + B".
     * @param mappings an object with mapping from variables to their
     *  replacements.
     * @return the formula, with all variables replaced.
     */
    private static String replaceVariables(String formula, ObjectNode mappings) {
        // If AMPL needs more rewriting of the formula than just variable name
        // replacement, we should parse the formula here.  For now, since
        // variables are word-shaped, we can hopefully get by with regular
        // expressions on the string representation of the formula.
        StringBuilder result = new StringBuilder(formula);
        Pattern id = Pattern.compile("\\b(\\w+)\\b");
        Matcher matcher = id.matcher(formula);
        int lengthDiff = 0;
        while (matcher.find()) {
            String var = matcher.group(1);
            JsonNode re = mappings.get(var);
            if (re != null) {
                int start = matcher.start(1) + lengthDiff;
                int end = matcher.end(1) + lengthDiff;
                result.replace(start, end, re.asText());
                lengthDiff += re.asText().length() - var.length();
            }
        }
        return result.toString();
    }
}

