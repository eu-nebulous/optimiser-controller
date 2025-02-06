package eu.nebulouscloud.optimiser.controller;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import eu.nebulouscloud.optimiser.kubevela.KubevelaAnalyzer;
import lombok.extern.slf4j.Slf4j;

/**
 * Generate AMPL from an app message.  This class could live as a couple of
 * methods in {@link NebulousApp} but we group all things AMPL in this file
 * for better readability.
 */
@Slf4j
public class AMPLGenerator {

    /**
     * Generate the app's metric list.
     */
    public static List<String> getMetricList(NebulousApp app) {
        return new ArrayList<>(usedMetrics(app));
    }

    /**
     * Generate AMPL code.
     *
     * @param app the application object.
     * @param kubevela the kubevela file, used to obtain default variable values.
     * @return AMPL code for the solver.
     */
    public static String generateAMPL(NebulousApp app, JsonNode kubevela) {
        final StringWriter result = new StringWriter();
        final PrintWriter out = new PrintWriter(result);
        out.format("# AMPL file for application '%s' with id %s%n", app.getRawName(), app.getUUID());
        out.println();

        generateVariablesSection(app, kubevela, out);
        generateMetricsSection(app, out);
        generateConstants(app, out);
        generatePerformanceIndicatorsSection(app, out);
        generateCostParameterSection(app, out);
        generateUtilityFunctions(app, out);
        generateConstraints(app, out);

        return result.toString();
    }

    // Utility functions that are constant go here
    private static void generateConstants(NebulousApp app, PrintWriter out) {
        out.println("# Constants");
        for (JsonNode f : app.getUtilityFunctions().values()) {
            if (!(f.get("type").asText().equals("constant")))
                continue;
            out.format("param %s;%n", f.get("name").asText());
        }
        out.println();
    }

    private static void generateConstraints(NebulousApp app, PrintWriter out) {
        out.println("# Constraints extracted from `/sloViolations`. For these we don't have name from GUI, must be created");
        int counter = 0;
        for (JsonNode slo : app.getEffectiveConstraints()) {
            out.format("subject to constraint_%d : ", counter);
            emitCondition(out, slo);
            out.println(";");
            counter = counter + 1;
        }
        out.println("# Constraints specified with `type: constraint` in `/utilityFunctions`");
        for (JsonNode c : app.getSpecifiedConstraints()) {
            String formula = replaceVariables(
                c.at("/expression/formula").asText(),
                c.withArray("/expression/variables"));
            String operator = c.at("/expression/operator").asText();
            if (operator.equals("==")) operator = "=";
            out.format("subject to %s :%n	%s %s 0;%n",
                c.at("/name").asText(), formula, operator);
        }
    }

    private static void emitCondition(PrintWriter out, JsonNode condition){
        if (condition.at("/isComposite").asBoolean()) {
            emitCompositeCondition(out, condition);
        } else {
            emitSimpleCondition(out, condition);
        }
    }

    private static void emitCompositeCondition(PrintWriter out, JsonNode condition) {
        String operator = condition.get("condition").asText();
        String intermission = "";
        for (JsonNode child : condition.withArray("children")) {
            out.print(intermission); intermission = " " + operator + " ";
            out.print("(");
            emitCondition(out, child);
            out.print(")");
        }
    }

    private static void emitSimpleCondition(PrintWriter out, JsonNode c) {
        if (c.at("/not").asBoolean()) { out.print("not "); }
        out.format("%s %s %s",
            c.get("metricName").asText(),
            c.get("operator").asText(),
            c.get("value").asText());
    }

    private static void generateUtilityFunctions(NebulousApp app, PrintWriter out) {
        out.println("# Utility functions");
        for (JsonNode f : app.getUtilityFunctions().values()) {
            if (f.get("type").asText().equals("constant"))
                continue;
            String formula = replaceVariables(
                f.at("/expression/formula").asText(),
                f.withArray("/expression/variables"));
            out.format("%s %s :%n	%s;%n",
                f.get("type").asText(), f.get("name").asText(),
                formula);
        }
        out.println();
        out.println("# Default utility function: specified in message to solver");
        out.println();
    }

    private static void generateCostParameterSection(NebulousApp app, PrintWriter out) {
        out.println("# Cost parameters - for all components, and use of node-candidates tensor");
        ArrayNode indicators = app.getRelevantPerformanceIndicators().withArray("PerformanceIndicators");
        if (indicators.size() == 0) return;
        for (JsonNode performanceIndicator : indicators) {
            int nVariables = performanceIndicator.withArray("variables").size();
            String name = performanceIndicator.at("/coefficientsName").textValue();
            out.format("param %s{1..%s};%n", name, nVariables + 1);
        }
        for (JsonNode performanceIndicator : indicators) {
            int iVariable = 1;
            String var_name = performanceIndicator.at("/name").textValue();
            String coeff_name = performanceIndicator.at("/coefficientsName").textValue();
            out.format("var %s = %s[1]", var_name, coeff_name);
            for (JsonNode var : performanceIndicator.withArray("/variables")) {
                iVariable++;
                out.format(" + %s[%s] * %s", coeff_name, iVariable, var.textValue());
            }
            out.println(";");
        }
        out.println();
        out.println("minimize costfunction:");
        String separator = "    ";
        for (JsonNode performanceIndicator : indicators) {
            out.print(separator); separator = " + ";
            out.print(performanceIndicator.at("/name").textValue());
        }
        out.println(";");
        out.println();
    }

    private static void generatePerformanceIndicatorsSection(NebulousApp app, PrintWriter out) {
        out.println("# Performance indicators = composite metrics that have at least one variable in their formula");
        for (final JsonNode m : app.getPerformanceIndicators().values()) {
            String name = m.get("name").asText();
            out.format("var %s;%n", name);
        }
        out.println("# Performance indicator formulas");
        for (final JsonNode m : app.getPerformanceIndicators().values()) {
            String name = m.get("name").asText();
            String formula = m.get("formula").asText();
            out.format("subject to define_%s : %s = %s;%n", name, name, formula);
        }
        out.println();
    }

    private static void generateMetricsSection(NebulousApp app, PrintWriter out) {
        Set<String> usedMetrics = usedMetrics(app);
        // Naming: in the JSON app message, "key" is the variable name used by
        // AMPL, "name" is a user-readable string not otherwise used.
        out.println("# Metrics.  Note that we only emit metrics that are in use.  Values will be provided by the solver.");
        out.println("## Raw metrics");
        for (final JsonNode m : app.getRawMetrics().values()) {
            String name = m.get("name").asText();
            if (usedMetrics.contains(name)) {
                out.format("param %s;	# %s%n", name, m.get("name").asText());
            }
        }

        out.println("## Composite metrics");
        for (final JsonNode m : app.getCompositeMetrics().values()) {
            String name = m.get("name").asText();
            if (usedMetrics.contains(name)) {
                out.format("param %s;	# %s%n", m.get("name").asText(), m.get("name").asText());
            }
        }
        out.println();
    }

    /**
     * Calculate all metrics that are actually used.
     *
     * @param app the NebulousApp.
     * @return The set of raw or composite metrics that are used in
     *  performance indicators, constraints or utility functions.
     */
    private static Set<String> usedMetrics(NebulousApp app) {
        Set<String> result = new HashSet<>();
        Set<String> allMetrics = new HashSet<>(app.getRawMetrics().keySet());
        allMetrics.addAll(app.getCompositeMetrics().keySet());
        // collect from performance indicators
        for (final JsonNode indicator : app.getPerformanceIndicators().values()) {
            indicator.withArray("arguments").elements()
                .forEachRemaining(node -> {
                    if (allMetrics.contains(node.asText()))
                        result.add(node.asText());
                });
        }
        // collect from constraints
        for (JsonNode slo : app.getEffectiveConstraints()) {
            slo.findValuesAsText("metricName").forEach(metricName -> {
                    if (allMetrics.contains(metricName)) result.add(metricName);
                });
        }
        // collect from utility functions
        for (JsonNode function : app.getUtilityFunctions().values()) {
            function.withArray("/expression/variables")
                .findValuesAsText("value")
                .forEach(name -> {
                    if (allMetrics.contains(name)) result.add(name);
                });
        }

        return result;
    }

    private static void generateVariablesSection(NebulousApp app, JsonNode kubevela, PrintWriter out) {
        out.println("# Variables");
        for (final JsonNode p : app.getKubevelaVariables().values()) {
            ObjectNode param = (ObjectNode) p;
            String paramName = param.get("key").textValue();
            String paramPath = param.get("path").textValue();
            String paramType = param.get("type").textValue();
            String paramMeaning = param.get("meaning").textValue();
            JsonNode defaultValue = kubevela.at(paramPath);
            // Even if these variables are sent over as "float", we know they
            // have to be treated as integers for kubevela (replicas, memory)
            // or SAL (cpu).  I.e., paramMeaning overrides paramType.
            boolean shouldBeInt = paramType.equals("int")
                                  || KubevelaAnalyzer.isKubevelaInteger(paramMeaning);
            ObjectNode value = (ObjectNode)param.get("value");
            if (paramType.equals("int") || paramType.equals("float")) {
                out.format("var %s", paramName);
                if (shouldBeInt) { out.print(" integer"); }
                if (value != null) {
                    String separator = "";
                    JsonNode lower = value.at("/lower_bound");
                    JsonNode upper = value.at("/higher_bound");
                    if (lower.isNumber()) {
                        out.format(" >= %s", lower.numberValue());
                        separator = ",";
                    }
                    if (upper.isNumber()) {
                        out.format("%s <= %s", separator, upper.numberValue());
                    }
                    if (!defaultValue.isMissingNode()) {
                        if (shouldBeInt){
                            try {
                                long number = KubevelaAnalyzer.kubevelaNumberToLong(defaultValue, paramMeaning);
                                out.format(" := %s", number);
                            } catch (NumberFormatException e) {
                                log.error("Unable to parse value '" + defaultValue
                                          + "' as integer (long) value of " + paramName);
                            }
                        } else {
                            if (defaultValue.isFloatingPointNumber()) {
                                out.format(" := %s", defaultValue.asDouble());
                            } else {
                                log.warn("Expected floating point default value at " + paramPath
                                         + " but got value " + defaultValue);
                            }
                        }
                    }
                }
                out.println(";");
            } else if (paramType.equals("string")) {
                out.println("# TODO not sure how to specify a string variable");
                out.format("var %s symbolic;%n", paramName);
            } else if (paramType.equals("array")) {
                out.format("# TODO generate entries for map '%s' at %s%n", paramName, paramPath);
            } else {
                log.info("Unknown variable parameter type: {}", paramType);
            }
        }
        out.println();
    }

    /**
     * Replace variables in formulas.
     *
     * @param formula a string like "A + B".
     * @param mappings an object with mapping from variables to their
     *  replacements.
     * @return the formula, with all variables replaced.
     */
    private static String replaceVariables(String formula, ArrayNode mappings) {
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

            JsonNode re = StreamSupport.stream(Spliterators.spliteratorUnknownSize(mappings.elements(), Spliterator.ORDERED), false)
                .filter(v -> v.at("/name").asText().equals(var))
                .findFirst().orElse(null);
            if (re != null) {
                String replacement = re.get("value").asText();
                int start = matcher.start(1) + lengthDiff;
                int end = matcher.end(1) + lengthDiff;
                result.replace(start, end, replacement);
                lengthDiff += replacement.length() - var.length();
            }
        }
        return result.toString();
    }
}

