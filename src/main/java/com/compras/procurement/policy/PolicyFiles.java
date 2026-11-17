package com.compras.procurement.policy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/** Reads a policy version from JSON. Strict: a misspelt field is an error, not a silently ignored rule. */
public final class PolicyFiles {

    private static final ObjectMapper STRICT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private PolicyFiles() {
    }

    /** The version shipped with the application: the starting point of a new installation, and the test fixture. */
    public static PolicySet builtin() {
        try (InputStream in = PolicyFiles.class.getResourceAsStream("/policy/policy-rules.json")) {
            if (in == null) {
                throw new IllegalStateException("policy/policy-rules.json is missing from the classpath");
            }
            return STRICT.readValue(in, PolicySet.class);
        } catch (IOException e) {
            throw new IllegalStateException("policy/policy-rules.json cannot be read: " + e.getMessage(), e);
        }
    }

    public static PolicySet parse(String json) {
        try {
            return STRICT.readValue(json, PolicySet.class);
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().split("\n")[0];
            throw new IllegalStateException("The policy is not valid JSON for a policy version: " + message);
        }
    }

    public static String json(PolicySet policy) {
        try {
            return STRICT.writerWithDefaultPrettyPrinter().writeValueAsString(policy);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
