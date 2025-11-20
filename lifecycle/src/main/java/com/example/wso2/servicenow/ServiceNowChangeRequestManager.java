package com.example.wso2.servicenow;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.example.wso2.util.JsonUtils;

/**
 * Manager for ServiceNow change request operations.
 * Handles change request creation, searching, approval checking, and commenting.
 */
public class ServiceNowChangeRequestManager {

    private static final Log log = LogFactory.getLog(ServiceNowChangeRequestManager.class);

    private final ServiceNowClient client;
    private final ServiceNowTagManager tagManager;

    public ServiceNowChangeRequestManager(ServiceNowClient client, ServiceNowTagManager tagManager) {
        this.client = client;
        this.tagManager = tagManager;
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
     * Creates a ServiceNow change request with tags for API name and version.
     * 
     * @param apiName API name
     * @param apiVersion API version
     * @param apiTag Tag for API name (e.g., "api:PetStore")
     * @param versionTag Tag for version (e.g., "version:1.0.0")
     * @throws Exception if creation fails
     */
    public void createChangeRequestWithTags(String apiName, String apiVersion, String apiTag, String versionTag)
            throws Exception {

        log.error("[createChangeRequestWithTags] Creating change request for API: " + apiName + " v" + apiVersion);

        // Step 1: Create the change request
        String changeSysId = createChangeRequest(apiName, apiVersion);
        log.error("[createChangeRequestWithTags] Created change request with sys_id: " + changeSysId);
        // Step 2: Create or reuse tags and attach them
        String[] tags = {apiTag, versionTag};
        for (String tagName : tags) {
            String tagSysId = tagManager.getOrCreateTag(tagName);
            log.error("[createChangeRequestWithTags] Tag '" + tagName + "' sys_id: " + tagSysId);

            tagManager.attachTagToChange(tagSysId, changeSysId);
            log.error("[createChangeRequestWithTags] Attached tag '" + tagName + "' to change request");
        }
    }

    /**
     * Gets the change request number for the given API by searching with tags.
     * Uses sys_tags reference field for efficient searching with both tags.
     * 
     * @param apiTag API tag (e.g., "api:PetStore")
     * @param versionTag Version tag (e.g., "version:1.0.0")
     * @return Change request number or null if not found
     * @throws Exception if search fails
     */
    public String findChangeRequestNumber(String apiTag, String versionTag) throws Exception {
        log.error("[findChangeRequestNumber] Searching for change request with tags: [" + apiTag + ", " + versionTag + "]");

        // Step 1: Find the sys_id for both tags
        String apiTagSysId = tagManager.findTagByName(apiTag);
        if (apiTagSysId == null) {
            log.error("[findChangeRequestNumber] API tag '" + apiTag + "' not found");
            return null;
        }

        String versionTagSysId = tagManager.findTagByName(versionTag);
        if (versionTagSysId == null) {
            log.error("[findChangeRequestNumber] Version tag '" + versionTag + "' not found");
            return null;
        }

        // Step 2: Search change_request using sys_tags with both tags (AND logic)
        String query = tagManager.buildTagQuery(apiTagSysId, versionTagSysId);
        String encodedQuery = ServiceNowClient.encodeQuery(query);
        
        log.error("[findChangeRequestNumber] Query: " + query);

        String params = "sysparm_query=" + encodedQuery + "&sysparm_fields=sys_id,number&sysparm_limit=1";
        String json = client.executeGet("/api/now/table/change_request", params);

        if (!JsonUtils.hasResults(json)) {
            log.error("[findChangeRequestNumber] No change request found with both tags");
            return null;
        }

        String number = JsonUtils.extractFirstField(json, "number");
        log.error("[findChangeRequestNumber] Found change request: " + number);
        return number;
    }

    /**
     * Checks if a change request exists for the given API tags.
     * 
     * @param apiTag API tag
     * @param versionTag Version tag
     * @return true if change request exists, false otherwise
     * @throws Exception if check fails
     */
    public boolean changeRequestExists(String apiTag, String versionTag) throws Exception {
        String changeRequestNumber = findChangeRequestNumber(apiTag, versionTag);
        return changeRequestNumber != null;
    }

    /**
     * Checks if the change request is in authorized state.
     * Uses sys_tags reference field for efficient searching with both tags.
     * 
     * @param apiTag API tag
     * @param versionTag Version tag
     * @return true if authorized, false otherwise
     * @throws Exception if check fails
     */
    public boolean isChangeRequestAuthorized(String apiTag, String versionTag) throws Exception {
        log.error("[isChangeRequestAuthorized] Checking authorized state");

        // Step 1: Find the sys_id for both tags
        String apiTagSysId = tagManager.findTagByName(apiTag);
        if (apiTagSysId == null) {
            log.error("[isChangeRequestAuthorized] API tag '" + apiTag + "' not found");
            return false;
        }

        String versionTagSysId = tagManager.findTagByName(versionTag);
        if (versionTagSysId == null) {
            log.error("[isChangeRequestAuthorized] Version tag '" + versionTag + "' not found");
            return false;
        }

        // Step 2: Search change_request with both tags AND state=authorized
        String tagQuery = tagManager.buildTagQuery(apiTagSysId, versionTagSysId);
        String fullQuery = tagQuery + "^state=authorized";
        String encodedQuery = ServiceNowClient.encodeQuery(fullQuery);
        
        log.error("[isChangeRequestAuthorized] Query: " + fullQuery);

        String params = "sysparm_query=" + encodedQuery + "&sysparm_fields=number,approval,state&sysparm_limit=1";
        String json = client.executeGet("/api/now/table/change_request", params);

        // If we find a result, it means CR exists with both tags and is in authorized state
        boolean isAuthorized = JsonUtils.hasResults(json);
        log.error("[isChangeRequestAuthorized] Authorized status: " + isAuthorized);
        return isAuthorized;
    }

    /**
     * Adds a comment to a change request documenting an unauthorized publish attempt.
     * 
     * @param changeRequestNumber Change request number
     * @param apiName API name
     * @param apiVersion API version
     * @param lcAction Lifecycle action attempted
     */
    public void addUnauthorizedAttemptComment(String changeRequestNumber, String apiName, String apiVersion,
            String lcAction) {

        log.error("[addUnauthorizedAttemptComment] Adding comment to CR: " + changeRequestNumber);

        try {
            // First, get the sys_id for the change request number
            String sysId = getChangeRequestSysId(changeRequestNumber);
            if (sysId == null) {
                log.error("[addUnauthorizedAttemptComment] Could not find sys_id for CR: " + changeRequestNumber);
                return;
            }

            String comment = "Unauthorized " + lcAction + " attempt for API " + apiName + " v" + apiVersion
                    + " was blocked. Change request must be in 'Authorized' state.";

            String body = "{\"work_notes\":\"" + JsonUtils.escapeJson(comment) + "\"}";

            client.executePatch("/api/now/table/change_request/" + sysId, body);
            log.error("[addUnauthorizedAttemptComment] Comment added successfully");

        } catch (Exception e) {
            String errorMsg = "[addUnauthorizedAttemptComment] Failed to add comment to CR: " + changeRequestNumber;
            log.error(errorMsg, e);
            // Don't throw exception - comment failure shouldn't break workflow
        }
    }

    /**
     * Gets the sys_id for a change request by its number.
     * 
     * @param changeRequestNumber Change request number
     * @return sys_id or null if not found
     * @throws Exception if search fails
     */
    private String getChangeRequestSysId(String changeRequestNumber) throws Exception {
        String query = "number=" + changeRequestNumber;
        String encodedQuery = ServiceNowClient.encodeQuery(query);

        String params = "sysparm_query=" + encodedQuery + "&sysparm_fields=sys_id&sysparm_limit=1";
        String json = client.executeGet("/api/now/table/change_request", params);

        if (!JsonUtils.hasResults(json)) {
            return null;
        }
        return JsonUtils.extractFirstField(json, "sys_id");
    }
}
