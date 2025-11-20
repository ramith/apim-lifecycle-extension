package com.example.wso2.servicenow;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.example.wso2.util.JsonUtils;

/**
 * Manager for ServiceNow tag (label) operations.
 * Handles tag creation, searching, and attachment to change requests.
 * 
 * <p><b>ServiceNow Tagging Architecture:</b></p>
 * <p>ServiceNow uses two complementary mechanisms for tags:</p>
 * <ol>
 *   <li><b>label table:</b> Stores tag definitions (name and sys_id)</li>
 *   <li><b>label_entry table:</b> Junction table linking tags to records (e.g., change requests)
 *       - Used by this class to enable programmatic queries via sys_tags reference field
 *       - Required for searching change requests by tags using REST API</li>
 *   <li><b>sys_tags field:</b> Text field on records (e.g., change_request) storing comma-separated tag names
 *       - Managed by ServiceNowChangeRequestManager
 *       - Required for tags to appear in ServiceNow GUI</li>
 * </ol>
 * 
 * <p><b>Division of Responsibilities:</b></p>
 * <ul>
 *   <li>This class (ServiceNowTagManager): Manages label table and label_entry junction records</li>
 *   <li>ServiceNowChangeRequestManager: Updates sys_tags field for GUI visibility</li>
 * </ul>
 * 
 * @see ServiceNowChangeRequestManager#createChangeRequestWithTags for the complete tagging workflow
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
     * Checks if a tag is already attached to a change request.
     * 
     * @param tagSysId Tag sys_id
     * @param changeSysId Change Request sys_id
     * @return true if already attached, false otherwise
     * @throws Exception if check fails
     */
    public boolean isTagAttached(String tagSysId, String changeSysId) throws Exception {
        String query = "label=" + tagSysId + "^table=change_request^table_key=" + changeSysId;
        String encodedQuery = ServiceNowClient.encodeQuery(query);

        String params = "sysparm_query=" + encodedQuery + "&sysparm_limit=1";
        String json = client.executeGet("/api/now/table/label_entry", params);

        return JsonUtils.hasResults(json);
    }

    /**
     * Creates a label_entry record linking a tag to a change request.
     * 
     * <p><b>Purpose:</b> label_entry is a junction table that enables programmatic queries
     * using ServiceNow's sys_tags reference field. This allows searching for change requests
     * by tags via REST API queries.</p>
     * 
     * <p><b>Duplicate Prevention:</b> Checks if the tag is already attached before attempting
     * to create the entry. Also catches ServiceNow's "Prevent Duplicate Label Entries" business
     * rule exception as a safety net.</p>
     * 
     * <p><b>Note:</b> This is separate from the sys_tags text field which is used for GUI display.
     * Both mechanisms work together: label_entry for API queries, sys_tags for UI visibility.</p>
     * 
     * @param tagSysId Tag sys_id (from label table)
     * @param changeSysId Change Request sys_id (from change_request table)
     * @throws Exception if attachment fails (non-duplicate errors)
     */
    public void attachTagToChange(String tagSysId, String changeSysId) throws Exception {
        // Check if tag is already attached to avoid duplicate label_entry
        if (isTagAttached(tagSysId, changeSysId)) {
            log.error("[attachTagToChange] Tag " + tagSysId + " is already attached to CR " + changeSysId + " - skipping");
            return;
        }

        String body = "{"
                + "\"label\":\"" + tagSysId + "\","
                + "\"table\":\"change_request\","
                + "\"table_key\":\"" + changeSysId + "\""
                + "}";

        try {
            client.executePost("/api/now/table/label_entry", body);
            log.error("[attachTagToChange] Successfully attached tag " + tagSysId + " to CR " + changeSysId);
        } catch (Exception e) {
            // If we get a 403 error about duplicate label entries, log and continue
            // This is a safety net in case isTagAttached() missed it due to timing
            if (e.getMessage() != null && e.getMessage().contains("Prevent Duplicate Label Entries")) {
                log.error("[attachTagToChange] Tag already attached (caught duplicate prevention rule) - continuing");
            } else {
                // Re-throw other exceptions
                throw e;
            }
        }
    }

    /**
     * Builds a ServiceNow query using sys_tags reference field for multiple tags with AND logic.
     * 
     * <p><b>Query Format:</b> Uses ServiceNow's reference field dot-walking syntax:<br>
     * <code>sys_tags.&lt;tag1_sys_id&gt;=&lt;tag1_sys_id&gt;^sys_tags.&lt;tag2_sys_id&gt;=&lt;tag2_sys_id&gt;</code></p>
     * 
     * <p><b>How it works:</b></p>
     * <ul>
     *   <li>sys_tags is a reference field that links to label_entry records</li>
     *   <li>Dot-walking (sys_tags.&lt;sys_id&gt;) checks if that specific tag is attached</li>
     *   <li>The ^ operator creates an AND condition between multiple tags</li>
     *   <li>This ensures only change requests with BOTH tags are returned</li>
     * </ul>
     * 
     * <p><b>Example:</b> If apiTagSysId="abc123" and versionTagSysId="def456", returns:<br>
     * <code>sys_tags.abc123=abc123^sys_tags.def456=def456</code></p>
     * 
     * @param apiTagSysId sys_id of the API tag (e.g., sys_id for "api:PetStore")
     * @param versionTagSysId sys_id of the version tag (e.g., sys_id for "version:1.0.0")
     * @return Query string (not URL-encoded) ready for ServiceNow REST API
     */
    public String buildTagQuery(String apiTagSysId, String versionTagSysId) {
        return "sys_tags." + apiTagSysId + "=" + apiTagSysId + "^"
                + "sys_tags." + versionTagSysId + "=" + versionTagSysId;
    }
}
