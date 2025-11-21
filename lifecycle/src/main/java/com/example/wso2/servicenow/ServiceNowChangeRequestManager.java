package com.example.wso2.servicenow;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.example.wso2.util.JsonUtils;

/**
 * Manager for ServiceNow change request operations.
 * Handles change request creation and validation.
 */
public class ServiceNowChangeRequestManager {

    private static final Log log = LogFactory.getLog(ServiceNowChangeRequestManager.class);

    private final ServiceNowClient client;

    public ServiceNowChangeRequestManager(ServiceNowClient client) {
        this.client = client;
    }

    /**
     * Creates a change_request record in ServiceNow.
     * 
     * @param apiName API name
     * @param apiVersion API version
     * @return sys_id of the created change_request
     * @throws Exception if creation fails
     */
    public String createChangeRequest(String apiName, String apiVersion) throws Exception {
        String description = "API " + apiName + " v" + apiVersion + " deployment";

        String body = "{"
                + "\"short_description\":\"" + JsonUtils.escapeJson(description) + "\","
                + "\"type\":\"normal\","
                + "\"description\":\"Change request for API lifecycle promotion\""
                + "}";

        String json = client.executePost("/api/now/table/change_request", body);
        return JsonUtils.extractFirstField(json, "sys_id");
    }

    /**
     * Gets complete change request details by sys_id.
     * 
     * @param sysId Change request sys_id
     * @return JSONObject with change request details or null if not found
     * @throws Exception if retrieval fails
     */
    public org.json.simple.JSONObject getChangeRequestBySysId(String sysId) throws Exception {
        log.error("[getChangeRequestBySysId] Retrieving change request for sys_id: " + sysId);

        try {
            String params = "sysparm_fields=number,state,short_description,sys_id";
            String json = client.executeGet("/api/now/table/change_request/" + sysId, params);
            
            org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
            org.json.simple.JSONObject response = (org.json.simple.JSONObject) parser.parse(json);
            org.json.simple.JSONObject result = (org.json.simple.JSONObject) response.get("result");
            
            if (result != null) {
                log.error("[getChangeRequestBySysId] Found change request: " + result.get("number"));
                return result;
            } else {
                log.error("[getChangeRequestBySysId] Change request not found for sys_id: " + sysId);
                return null;
            }
        } catch (Exception e) {
            log.error("[getChangeRequestBySysId] Error retrieving change request for sys_id: " + sysId, e);
            throw e;
        }
    }

    /**
     * Checks if a given state is considered authorized (approved).
     * 
     * @param state ServiceNow change request state
     * @return true if state is authorized, false otherwise
     */
    public boolean isStateAuthorized(String state) {
        // Check against authorized state codes
        // State codes: -1=New, 0=Assess, 1=Authorize, 2=Scheduled, 3=Implement
        boolean authorized = state != null && (
            "1".equals(state) ||        // Authorize
            "2".equals(state) ||        // Scheduled
            "3".equals(state) ||        // Implement
            "authorized".equalsIgnoreCase(state) ||
            "approved".equalsIgnoreCase(state)
        );
        log.error("[isStateAuthorized] State: " + state + ", Authorized: " + authorized);
        return authorized;
    }
}

