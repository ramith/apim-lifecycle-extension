package com.example.wso2.servicenow;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.example.wso2.util.JsonUtils;

/**
 * Manager for ServiceNow tag (label) operations.
 * Handles tag creation, searching, and attachment to change requests.
 */
public class ServiceNowTagManager {

    private static final Log log = LogFactory.getLog(ServiceNowTagManager.class);
    
    private final ServiceNowClient client;

    public ServiceNowTagManager(ServiceNowClient client) {
        this.client = client;
    }

    /**
     * Retrieves an existing tag by name or creates one if it doesn't exist.
     * 
     * @param labelName Tag name
     * @return sys_id of the tag
     * @throws Exception if operation fails
     */
    public String getOrCreateTag(String labelName) throws Exception {
        String existing = findTagByName(labelName);
        if (existing != null) {
            log.error("[getOrCreateTag] Reusing existing tag: " + labelName);
            return existing;
        }
        log.error("[getOrCreateTag] Creating new tag: " + labelName);
        return createTag(labelName);
    }

    /**
     * Searches the label table for a given tag name.
     * 
     * @param labelName Tag name
     * @return sys_id of the tag or null if not found
     * @throws Exception if search fails
     */
    public String findTagByName(String labelName) throws Exception {
        String query = "name=" + labelName;
        String encodedQuery = ServiceNowClient.encodeQuery(query);

        String params = "sysparm_query=" + encodedQuery + "&sysparm_fields=sys_id,name&sysparm_limit=1";
        String json = client.executeGet("/api/now/table/label", params);

        if (!JsonUtils.hasResults(json)) {
            return null;
        }
        return JsonUtils.extractFirstField(json, "sys_id");
    }

    /**
     * Creates a new tag in the label table.
     * 
     * @param labelName Tag name
     * @return sys_id of the created tag
     * @throws Exception if creation fails
     */
    public String createTag(String labelName) throws Exception {
        String body = "{"
                + "\"name\":\"" + JsonUtils.escapeJson(labelName) + "\""
                + "}";

        String json = client.executePost("/api/now/table/label", body);
        return JsonUtils.extractFirstField(json, "sys_id");
    }

    /**
     * Creates a label_entry linking a tag to a change request.
     * 
     * @param tagSysId Tag sys_id
     * @param changeSysId Change Request sys_id
     * @throws Exception if attachment fails
     */
    public void attachTagToChange(String tagSysId, String changeSysId) throws Exception {
        String body = "{"
                + "\"label\":\"" + tagSysId + "\","
                + "\"table\":\"change_request\","
                + "\"table_key\":\"" + changeSysId + "\""
                + "}";

        client.executePost("/api/now/table/label_entry", body);
    }

    /**
     * Builds a ServiceNow query using sys_tags reference field for multiple tags with AND logic.
     * Format: sys_tags.<tag1_sys_id>=<tag1_sys_id>^sys_tags.<tag2_sys_id>=<tag2_sys_id>
     * 
     * @param apiTagSysId sys_id of the API tag
     * @param versionTagSysId sys_id of the version tag
     * @return Query string (not URL-encoded)
     */
    public String buildTagQuery(String apiTagSysId, String versionTagSysId) {
        return "sys_tags." + apiTagSysId + "=" + apiTagSysId + "^"
                + "sys_tags." + versionTagSysId + "=" + versionTagSysId;
    }
}
