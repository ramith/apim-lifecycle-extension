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
     * <p><b>ServiceNow Tagging Mechanism:</b></p>
     * <p>ServiceNow uses the <code>label_entry</code> junction table to link tags (labels) to records.
     * The UI automatically displays tags from label_entry records in the Tags column.</p>
     * 
     * <p><b>Process:</b></p>
     * <ol>
     *   <li>Create change request record</li>
     *   <li>Create or reuse tag records in the label table</li>
     *   <li>Create label_entry records linking tags to the change request</li>
     * </ol>
     * 
     * <p><b>Result:</b> Tags appear in both GUI (Tags column) and are searchable via sys_tags reference field.</p>
     * 
     * @param apiName API name
     * @param apiVersion API version
     * @param apiTag Tag for API name (e.g., "api:PetStore")
     * @param versionTag Tag for version (e.g., "version:1.0.0")
     * @return sys_id of the created change request
     * @throws Exception if creation fails
     */
    public String createChangeRequestWithTags(String apiName, String apiVersion, String apiTag, String versionTag)
            throws Exception {

        log.error("[createChangeRequestWithTags] Creating change request for API: " + apiName + " v" + apiVersion);

        // Step 1: Create the change request
        String changeSysId = createChangeRequest(apiName, apiVersion);
        log.error("[createChangeRequestWithTags] Created change request with sys_id: " + changeSysId);
        
        // Step 2: Create or reuse tags and attach them via label_entry
        String[] tagNames = {apiTag, versionTag};
        
        for (String tagName : tagNames) {
            String tagSysId = tagManager.getOrCreateTag(tagName);
            log.error("[createChangeRequestWithTags] Tag '" + tagName + "' sys_id: " + tagSysId);
            
            // Create label_entry record - this makes tags visible in UI and searchable via API
            tagManager.attachTagToChange(tagSysId, changeSysId);
            log.error("[createChangeRequestWithTags] Attached tag '" + tagName + "' via label_entry");
        }
        
        log.error("[createChangeRequestWithTags] All tags attached - tags should now be visible in ServiceNow UI");
        return changeSysId;
    }

    /**
     * Gets the change request number for the given API by searching with tags.
     * Uses a single ServiceNow query with OR operator to find change requests with BOTH tags.
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

        // Step 2: Single query with OR to get all label_entry records for both tags
        // Query: table=change_request^label=<apiTagSysId>^ORlabel=<versionTagSysId>
        String query = "table=change_request^label=" + apiTagSysId + "^ORlabel=" + versionTagSysId;
        String encodedQuery = ServiceNowClient.encodeQuery(query);
        
        log.error("[findChangeRequestNumber] Query: " + query);

        String params = "sysparm_query=" + encodedQuery + "&sysparm_fields=table_key,label&sysparm_limit=1000";
        String json = client.executeGet("/api/now/table/label_entry", params);

        if (!JsonUtils.hasResults(json)) {
            log.error("[findChangeRequestNumber] No change requests found with tags");
            return null;
        }

        // Step 3: Find change request that has BOTH tags
        String changeSysId = findChangeRequestWithBothTags(json, apiTagSysId, versionTagSysId);
        if (changeSysId == null) {
            log.error("[findChangeRequestNumber] No change request found with both tags");
            return null;
        }

        // Step 4: Get the change request number
        String params2 = "sysparm_fields=number";
        String json2 = client.executeGet("/api/now/table/change_request/" + changeSysId, params2);
        
        String number = JsonUtils.extractFirstField(json2, "number");
        log.error("[findChangeRequestNumber] Found change request: " + number + " (sys_id: " + changeSysId + ")");
        return number;
    }

    /**
     * Finds a change request that has both required tags from a single OR query result.
     * Builds a map of table_key -> labels and finds the one with both tag sys_ids.
     * 
     * @param json Label_entry query results
     * @param apiTagSysId API tag sys_id
     * @param versionTagSysId Version tag sys_id
     * @return Change request sys_id or null if no match
     */
    private String findChangeRequestWithBothTags(String json, String apiTagSysId, String versionTagSysId) {
        // Build map: table_key -> Set<label_sys_id>
        java.util.Map<String, java.util.Set<String>> changeRequestTags = new java.util.HashMap<>();
        
        try {
            int startIndex = 0;
            while (true) {
                // Find the start of a result object
                int objectStart = json.indexOf("{", startIndex);
                if (objectStart == -1) break;
                
                // Find the end of this object
                int objectEnd = json.indexOf("}", objectStart);
                if (objectEnd == -1) break;
                
                String object = json.substring(objectStart, objectEnd + 1);
                
                // Extract table_key
                int tableKeyIndex = object.indexOf("\"table_key\":\"");
                if (tableKeyIndex == -1) {
                    startIndex = objectEnd + 1;
                    continue;
                }
                int tableKeyStart = tableKeyIndex + "\"table_key\":\"".length();
                int tableKeyEnd = object.indexOf("\"", tableKeyStart);
                String tableKey = object.substring(tableKeyStart, tableKeyEnd);
                
                // Extract label
                int labelIndex = object.indexOf("\"label\":\"");
                if (labelIndex == -1) {
                    startIndex = objectEnd + 1;
                    continue;
                }
                int labelStart = labelIndex + "\"label\":\"".length();
                int labelEnd = object.indexOf("\"", labelStart);
                String label = object.substring(labelStart, labelEnd);
                
                // Add to map
                changeRequestTags.computeIfAbsent(tableKey, k -> new java.util.HashSet<>()).add(label);
                
                startIndex = objectEnd + 1;
            }
        } catch (Exception e) {
            log.error("[findChangeRequestWithBothTags] Error parsing JSON", e);
            return null;
        }
        
        // Find change request with both tags
        for (java.util.Map.Entry<String, java.util.Set<String>> entry : changeRequestTags.entrySet()) {
            if (entry.getValue().contains(apiTagSysId) && entry.getValue().contains(versionTagSysId)) {
                log.error("[findChangeRequestWithBothTags] Found change request: " + entry.getKey());
                return entry.getKey();
            }
        }
        
        return null;
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
     * Gets the sys_id for a change request by searching with API tags.
     * 
     * @param apiTag API tag
     * @param versionTag Version tag
     * @return sys_id or null if not found
     * @throws Exception if search fails
     */
    public String getChangeRequestSysId(String apiTag, String versionTag) throws Exception {
        log.error("[getChangeRequestSysId] Searching for change request sys_id with tags: [" + apiTag + ", " + versionTag + "]");

        // Find the sys_id for both tags
        String apiTagSysId = tagManager.findTagByName(apiTag);
        if (apiTagSysId == null) {
            log.error("[getChangeRequestSysId] API tag '" + apiTag + "' not found");
            return null;
        }

        String versionTagSysId = tagManager.findTagByName(versionTag);
        if (versionTagSysId == null) {
            log.error("[getChangeRequestSysId] Version tag '" + versionTag + "' not found");
            return null;
        }

        // Query label_entry with OR to get all records for both tags
        String query = "table=change_request^label=" + apiTagSysId + "^ORlabel=" + versionTagSysId;
        String encodedQuery = ServiceNowClient.encodeQuery(query);
        
        String params = "sysparm_query=" + encodedQuery + "&sysparm_fields=table_key,label&sysparm_limit=1000";
        String json = client.executeGet("/api/now/table/label_entry", params);

        if (!JsonUtils.hasResults(json)) {
            log.error("[getChangeRequestSysId] No change requests found with tags");
            return null;
        }

        // Find change request that has BOTH tags
        String changeSysId = findChangeRequestWithBothTags(json, apiTagSysId, versionTagSysId);
        if (changeSysId != null) {
            log.error("[getChangeRequestSysId] Found change request sys_id: " + changeSysId);
        }
        return changeSysId;
    }

    /**
     * Checks if a change request exists by directly querying with sys_id.
     * This is faster than searching by tags when sys_id is already known.
     * 
     * @param sysId Change request sys_id
     * @return true if change request exists, false otherwise
     */
    public boolean changeRequestExistsBySysId(String sysId) {
        log.error("[changeRequestExistsBySysId] Checking if change request exists with sys_id: " + sysId);
        
        try {
            String params = "sysparm_fields=sys_id";
            String json = client.executeGet("/api/now/table/change_request/" + sysId, params);
            
            // If we get a valid response with sys_id, the CR exists
            String retrievedSysId = JsonUtils.extractFirstField(json, "sys_id");
            boolean exists = (retrievedSysId != null);
            
            log.error("[changeRequestExistsBySysId] Change request exists: " + exists);
            return exists;
        } catch (Exception e) {
            log.error("[changeRequestExistsBySysId] Error checking change request existence for sys_id: " + sysId, e);
            return false;
        }
    }

    /**
     * Checks authorization status by directly retrieving the change request using sys_id.
     * Returns both the change request number and authorization status in a single API call.
     * 
     * @param sysId Change request sys_id from cache
     * @return String array [changeRequestNumber, isAuthorized] or null if not found
     * @throws Exception if retrieval fails
     */
    public String[] checkAuthorizationBySysId(String sysId) throws Exception {
        log.error("[checkAuthorizationBySysId] Checking authorization for sys_id: " + sysId);

        try {
            // Get both number and state in a single API call
            String params = "sysparm_fields=number,state";
            String json = client.executeGet("/api/now/table/change_request/" + sysId, params);
            
            String number = JsonUtils.extractFirstField(json, "number");
            String state = JsonUtils.extractFirstField(json, "state");
            
            if (number != null) {
                boolean isAuthorized = "authorized".equals(state);
                log.error("[checkAuthorizationBySysId] CR: " + number + ", State: " + state + ", Authorized: " + isAuthorized);
                return new String[]{number, String.valueOf(isAuthorized)};
            } else {
                log.error("[checkAuthorizationBySysId] Change request not found for sys_id: " + sysId);
                return null;
            }
        } catch (Exception e) {
            log.error("[checkAuthorizationBySysId] Error retrieving change request for sys_id: " + sysId, e);
            return null;
        }
    }
    
    /**
     * Gets the change request number by sys_id (used when retrieving from cache).
     * 
     * @param sysId Change request sys_id
     * @return Change request number or null if not found
     * @throws Exception if retrieval fails
     */
    public String getChangeRequestNumberBySysId(String sysId) throws Exception {
        log.error("[getChangeRequestNumberBySysId] Retrieving change request number for sys_id: " + sysId);

        try {
            String params = "sysparm_fields=number";
            String json = client.executeGet("/api/now/table/change_request/" + sysId, params);
            
            String number = JsonUtils.extractFirstField(json, "number");
            if (number != null) {
                log.error("[getChangeRequestNumberBySysId] Found change request: " + number);
            } else {
                log.error("[getChangeRequestNumberBySysId] Change request not found for sys_id: " + sysId);
            }
            return number;
        } catch (Exception e) {
            log.error("[getChangeRequestNumberBySysId] Error retrieving change request for sys_id: " + sysId, e);
            return null;
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
