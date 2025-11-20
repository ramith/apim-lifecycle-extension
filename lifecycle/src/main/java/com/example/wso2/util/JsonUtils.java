package com.example.wso2.util;

/**
 * Utility class for JSON operations.
 */
public class JsonUtils {

    /**
     * Extracts a JSON string value from ServiceNow responses.
     * 
     * @param json Response body
     * @param field Field name
     * @return First string value or null
     */
    public static String extractFirstField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx == -1)
            return null;
        int colon = json.indexOf(":", idx);
        int quoteStart = json.indexOf("\"", colon + 1) + 1;
        int quoteEnd = json.indexOf("\"", quoteStart);
        if (quoteStart <= 0 || quoteEnd <= quoteStart)
            return null;
        return json.substring(quoteStart, quoteEnd);
    }

    /**
     * Escapes quotes and backslashes for JSON strings.
     * 
     * @param s Input string
     * @return Escaped string
     */
    public static String escapeJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Checks if JSON response contains results.
     * 
     * @param json Response body
     * @return true if results are present, false otherwise
     */
    public static boolean hasResults(String json) {
        return json.contains("\"result\":[{");
    }

    /**
     * Checks if JSON response has empty results.
     * 
     * @param json Response body
     * @return true if results are empty, false otherwise
     */
    public static boolean isEmpty(String json) {
        return json.contains("\"result\":[]") || json.contains("\"result\": []");
    }
}
