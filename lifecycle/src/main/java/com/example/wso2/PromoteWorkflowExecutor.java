package com.example.wso2;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.WorkflowResponse;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.workflow.APIStateChangeSimpleWorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.APIStateWorkflowDTO;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;

import org.apache.axis2.util.URL;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.HttpHeaders;

public class PromoteWorkflowExecutor extends APIStateChangeSimpleWorkflowExecutor {
    
    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(PromoteWorkflowExecutor.class);
	
    // Those values are initialized from the internal APIM properties/secrets
    private String serviceNowBaseUrl;
    private String serviceNowUserName;
    private String serviceNowPassword;
    private String stateList;

    @Override
    public WorkflowResponse execute(WorkflowDTO workflowDTO) throws WorkflowException {
        log.error("[PromoteWorkflowExecutor] ===== Starting execute() method =====");
        
        if (workflowDTO == null) {
            String errorMsg = "[PromoteWorkflowExecutor] WorkflowDTO is null";
            log.error(errorMsg);
            throw new WorkflowException(errorMsg);
        }

        APIStateWorkflowDTO apiStateWorkFlowDTO = (APIStateWorkflowDTO) workflowDTO;
        
        String currentState = apiStateWorkFlowDTO.getApiCurrentState();
        String apiName = apiStateWorkFlowDTO.getApiName();
        String apiVersion = apiStateWorkFlowDTO.getApiVersion();
        String apiLifeCycleAction = apiStateWorkFlowDTO.getApiLCAction();
        
        log.info("[PromoteWorkflowExecutor] Processing workflow - API: '" + apiName + "' v" + apiVersion + 
                 ", Current State: '" + currentState + "', LC Action: '" + apiLifeCycleAction + "'");
        
        log.error("[PromoteWorkflowExecutor] ServiceNow Base URL: " + serviceNowBaseUrl);
        log.error("[PromoteWorkflowExecutor] ServiceNow Username: " + serviceNowUserName);
        
        try {
            // Check if API is in CREATED state
            if ("CREATED".equals(currentState)) {
                log.info("[PromoteWorkflowExecutor] API in CREATED state, checking ServiceNow change request");
                log.error("[PromoteWorkflowExecutor] Calling handleCreatedState()");
                handleCreatedState(apiName, apiVersion);
            }
            
            // Check if lifecycle action is Publish or Re-Publish
            if (apiLifeCycleAction != null && 
                ("Publish".equalsIgnoreCase(apiLifeCycleAction.trim()) || "Re-Publish".equalsIgnoreCase(apiLifeCycleAction.trim()))) {
                log.info("[PromoteWorkflowExecutor] Publish action detected, validating ServiceNow approval");
                log.error("[PromoteWorkflowExecutor] Calling handlePublishAction()");
                
                boolean isApproved = handlePublishAction(apiName, apiVersion, apiLifeCycleAction);
                
                if (!isApproved) {
                    log.warn("[PromoteWorkflowExecutor] *** WORKFLOW REJECTED *** - No approval for API: '" + apiName + "' v" + apiVersion);
                    workflowDTO.setStatus(WorkflowStatus.REJECTED);
                    return complete(workflowDTO);
                }
            }
            
            // Approve and execute default workflow actions
            log.info("[PromoteWorkflowExecutor] *** WORKFLOW APPROVED *** for API: '" + apiName + "' v" + apiVersion);
            workflowDTO.setStatus(WorkflowStatus.APPROVED);
            log.error("[PromoteWorkflowExecutor] ===== Exiting execute() with APPROVED status =====");
            return complete(workflowDTO);
            
        } catch (WorkflowException we) {
            log.error("[PromoteWorkflowExecutor] WorkflowException in execute() for API: '" + apiName + "' v" + apiVersion, we);
            throw we;
        } catch (Exception e) {
            String errorMsg = "[PromoteWorkflowExecutor] Unexpected exception in execute() for API: '" + apiName + "' v" + apiVersion;
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
    }
    
    private boolean handlePublishAction(String apiName, String apiVersion, String lcAction) throws WorkflowException {
        log.error("[handlePublishAction] ----- Starting approval validation -----");
        log.error("[handlePublishAction] API: '" + apiName + "' v" + apiVersion + ", Action: '" + lcAction + "'");
        
        String apiTag = "api:" + apiName;
        String versionTag = "version:" + apiVersion;
        
        log.error("[handlePublishAction] Tags: [" + apiTag + ", " + versionTag + "]");
        
        String changeRequestNumber = getChangeRequestNumber(apiName, apiVersion, apiTag, versionTag);
        
        if (changeRequestNumber == null) {
            log.error("[handlePublishAction] FAIL: No change request found for API: '" + apiName + "' v" + apiVersion);
            log.error("[handlePublishAction] Cannot proceed with " + lcAction + " - change request must exist");
            return false;
        }
        
        log.info("[handlePublishAction] Found change request: " + changeRequestNumber);
        
        boolean isApproved = checkIfChangeRequestApproved(apiName, apiVersion, apiTag, versionTag);
        
        if (!isApproved) {
            log.warn("[handlePublishAction] FAIL: Change request " + changeRequestNumber + " is NOT approved");
            log.warn("[handlePublishAction] Rejecting " + lcAction + " action for API: '" + apiName + "' v" + apiVersion);
            
            log.error("[handlePublishAction] Adding unauthorized attempt comment to CR: " + changeRequestNumber);
            addCommentToChangeRequest(changeRequestNumber, apiName, apiVersion, lcAction);
            
            return false;
        }
        
        log.info("[handlePublishAction] SUCCESS: Change request " + changeRequestNumber + " is APPROVED");
        log.info("[handlePublishAction] Proceeding with " + lcAction + " for API: '" + apiName + "' v" + apiVersion);
        log.error("[handlePublishAction] ----- Approval validation complete -----");
        return true;
    }
    
    private String getChangeRequestNumber(String apiName, String apiVersion, String apiTag, String versionTag) 
            throws WorkflowException {
        
        try {
            // Find change requests that have both required tags
            String apiTagSysId = findTagByName(apiTag);
            String versionTagSysId = findTagByName(versionTag);
            
            if (apiTagSysId == null || versionTagSysId == null) {
                log.error("[getChangeRequestNumber] Tags not found - cannot query change requests");
                return null;
            }
            
            // Query label_entry to find change_request with both tags
            String changeRequestSysId = findChangeRequestByTags(apiTagSysId, versionTagSysId);
            
            if (changeRequestSysId == null) {
                return null;
            }
            
            // Get the change request number from sys_id
            return getChangeRequestNumberBySysId(changeRequestSysId);
            
        } catch (Exception e) {
            log.error("Error getting change request number", e);
            return null;
        }
    }
    
    private String findChangeRequestByTags(String apiTagSysId, String versionTagSysId) throws Exception {
        // Query label_entry for records with first tag
        String query = "label=" + apiTagSysId + "^table=change_request";
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        String queryURL = serviceNowBaseUrl + "/api/now/table/label_entry?sysparm_query=" + encodedQuery + 
                         "&sysparm_fields=table_key";
        
        log.error("[findChangeRequestByTags] Query URL: " + queryURL);
        
        URL queryEndpointURL = new URL(queryURL);
        HttpClient queryClient = APIUtil.getHttpClient(queryEndpointURL.getPort(), queryEndpointURL.getProtocol());
        HttpGet httpGet = new HttpGet(queryURL);
        
        String auth = serviceNowUserName + ":" + serviceNowPassword;
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
        httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
        httpGet.setHeader(HttpHeaders.ACCEPT, "application/json");
        
        HttpResponse queryResponse = queryClient.execute(httpGet);
        int queryStatusCode = queryResponse.getStatusLine().getStatusCode();
        
        if (queryStatusCode == 200) {
            String responseBody = org.apache.http.util.EntityUtils.toString(queryResponse.getEntity());
            
            if (responseBody.contains("\"result\":[]") || responseBody.contains("\"result\": []")) {
                return null;
            }
            
            // Extract all table_key values and check if any have the version tag
            String tableKey = extractFirstField(responseBody, "table_key");
            
            if (tableKey != null && hasTag(tableKey, versionTagSysId)) {
                return tableKey;
            }
        }
        
        return null;
    }
    
    private boolean hasTag(String changeRequestSysId, String tagSysId) throws Exception {
        String query = "label=" + tagSysId + "^table=change_request^table_key=" + changeRequestSysId;
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        String queryURL = serviceNowBaseUrl + "/api/now/table/label_entry?sysparm_query=" + encodedQuery + 
                         "&sysparm_limit=1";
        
        URL queryEndpointURL = new URL(queryURL);
        HttpClient queryClient = APIUtil.getHttpClient(queryEndpointURL.getPort(), queryEndpointURL.getProtocol());
        HttpGet httpGet = new HttpGet(queryURL);
        
        String auth = serviceNowUserName + ":" + serviceNowPassword;
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
        httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
        httpGet.setHeader(HttpHeaders.ACCEPT, "application/json");
        
        HttpResponse response = queryClient.execute(httpGet);
        String responseBody = org.apache.http.util.EntityUtils.toString(response.getEntity());
        
        return response.getStatusLine().getStatusCode() == 200 && 
               !responseBody.contains("\"result\":[]") && 
               !responseBody.contains("\"result\": []");
    }
    
    private String getChangeRequestNumberBySysId(String sysId) throws Exception {
        String query = "sys_id=" + sysId;
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        String queryURL = serviceNowBaseUrl + "/api/now/table/change_request?sysparm_query=" + encodedQuery + 
                         "&sysparm_fields=number";
        
        URL queryEndpointURL = new URL(queryURL);
        HttpClient queryClient = APIUtil.getHttpClient(queryEndpointURL.getPort(), queryEndpointURL.getProtocol());
        HttpGet httpGet = new HttpGet(queryURL);
        
        String auth = serviceNowUserName + ":" + serviceNowPassword;
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
        httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
        httpGet.setHeader(HttpHeaders.ACCEPT, "application/json");
        
        HttpResponse response = queryClient.execute(httpGet);
        String responseBody = org.apache.http.util.EntityUtils.toString(response.getEntity());
        
        if (response.getStatusLine().getStatusCode() == 200) {
            return extractFirstField(responseBody, "number");
        }
        return null;
    }
    
    private void addCommentToChangeRequest(String changeRequestNumber, String apiName, String apiVersion, String lcAction) {
        
        try {
            String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new java.util.Date());
            String commentText = String.format(
                "UNAUTHORIZED PUBLISH ATTEMPT: An attempt was made to %s API '%s' version '%s' without proper approval. " +
                "Attempt timestamp: %s. Please ensure proper approvals are in place before attempting to change API lifecycle state.",
                lcAction, apiName, apiVersion, timestamp);
            
            // Get sys_id of the change request
            String sysId = getChangeRequestSysId(changeRequestNumber);
            
            if (sysId == null) {
                log.error("Could not retrieve sys_id for change request: " + changeRequestNumber);
                return;
            }
            
            // Add comment using ServiceNow API
            String commentURL = serviceNowBaseUrl + "/api/now/table/change_request/" + sysId;
            
            URL commentEndpointURL = new URL(commentURL);
            HttpClient httpClient = APIUtil.getHttpClient(commentEndpointURL.getPort(), commentEndpointURL.getProtocol());
            HttpPatch httpPatch = new HttpPatch(commentURL);
            
            // Set ServiceNow API headers with Basic Authentication
            String auth = serviceNowUserName + ":" + serviceNowPassword;
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
            httpPatch.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
            httpPatch.setHeader(HttpHeaders.ACCEPT, "application/json");
            httpPatch.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            
            // Create JSON payload to add work notes
            String jsonPayload = String.format("{\"work_notes\":\"%s\"}", commentText.replace("\"", "\\\""));
            
            httpPatch.setEntity(new StringEntity(jsonPayload));
            
            HttpResponse response = httpClient.execute(httpPatch);
            int statusCode = response.getStatusLine().getStatusCode();
            
            if (statusCode == 200) {
                log.info("Successfully added comment to ServiceNow change request: " + changeRequestNumber);
            } else {
                log.error("Failed to add comment to ServiceNow change request. Status: " + statusCode);
            }
            
        } catch (Exception e) {
            log.error("Error adding comment to ServiceNow change request: " + changeRequestNumber, e);
        }
    }
    
    private String getChangeRequestSysId(String changeRequestNumber) throws WorkflowException {
        
        try {
            // URL encode the query to handle spaces and special characters
            String query = "number=" + changeRequestNumber;
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String queryURL = serviceNowBaseUrl + "/api/now/table/change_request?sysparm_query=" + encodedQuery + 
                            "&sysparm_fields=sys_id";
            
            log.error("[getChangeRequestSysId] Raw query: " + query);
            log.error("[getChangeRequestSysId] Encoded query: " + encodedQuery);
            log.error("[getChangeRequestSysId] Query URL: " + queryURL);
            
            URL queryEndpointURL = new URL(queryURL);
            HttpClient queryClient = APIUtil.getHttpClient(queryEndpointURL.getPort(), queryEndpointURL.getProtocol());
            HttpGet httpGet = new HttpGet(queryURL);
            
            // Set ServiceNow API headers with Basic Authentication
            String auth = serviceNowUserName + ":" + serviceNowPassword;
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
            httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
            httpGet.setHeader(HttpHeaders.ACCEPT, "application/json");
            
            HttpResponse queryResponse = queryClient.execute(httpGet);
            int queryStatusCode = queryResponse.getStatusLine().getStatusCode();
            
            if (queryStatusCode == 200) {
                String responseBody = org.apache.http.util.EntityUtils.toString(queryResponse.getEntity());
                
                // Extract sys_id from response
                // Response format: {"result":[{"sys_id":"abc123"}]}
                int sysIdIndex = responseBody.indexOf("\"sys_id\":\"");
                if (sysIdIndex != -1) {
                    int startIndex = sysIdIndex + 10; // Length of "\"sys_id\":\""
                    int endIndex = responseBody.indexOf("\"", startIndex);
                    if (endIndex != -1) {
                        return responseBody.substring(startIndex, endIndex);
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("Error getting change request sys_id", e);
            return null;
        }
    }
    
    private boolean checkIfChangeRequestApproved(String apiName, String apiVersion, String apiTag, String versionTag) 
            throws WorkflowException {
        
        try {
            // Find change requests that have both required tags
            String apiTagSysId = findTagByName(apiTag);
            String versionTagSysId = findTagByName(versionTag);
            
            if (apiTagSysId == null || versionTagSysId == null) {
                log.error("Tags not found for API: " + apiName + " version: " + apiVersion);
                return false;
            }
            
            // Query label_entry to find change_request with both tags
            String changeRequestSysId = findChangeRequestByTags(apiTagSysId, versionTagSysId);
            
            if (changeRequestSysId == null) {
                log.error("No change request found for API: " + apiName + " version: " + apiVersion);
                return false;
            }
            
            // Get approval status
            String query = "sys_id=" + changeRequestSysId;
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String queryURL = serviceNowBaseUrl + "/api/now/table/change_request?sysparm_query=" + encodedQuery + 
                            "&sysparm_fields=approval";
            
            log.error("[checkIfChangeRequestApproved] Query URL: " + queryURL);
            
            URL queryEndpointURL = new URL(queryURL);
            HttpClient queryClient = APIUtil.getHttpClient(queryEndpointURL.getPort(), queryEndpointURL.getProtocol());
            HttpGet httpGet = new HttpGet(queryURL);
            
            // Set ServiceNow API headers with Basic Authentication
            String auth = serviceNowUserName + ":" + serviceNowPassword;
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
            httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
            httpGet.setHeader(HttpHeaders.ACCEPT, "application/json");
            
            HttpResponse queryResponse = queryClient.execute(httpGet);
            int queryStatusCode = queryResponse.getStatusLine().getStatusCode();
            
            if (queryStatusCode == 200) {
                String responseBody = org.apache.http.util.EntityUtils.toString(queryResponse.getEntity());
                log.info("ServiceNow change request approval check response: " + responseBody);
                
                // Check approval status
                if (responseBody.contains("\"approval\":\"approved\"")) {
                    log.info("Change request is approved for API: " + apiName + " version: " + apiVersion);
                    return true;
                } else {
                    log.warn("Change request is not approved for API: " + apiName + " version: " + apiVersion);
                    return false;
                }
                
            } else {
                log.error("Failed to query ServiceNow for approval status. Status: " + queryStatusCode);
                return false;
            }
            
        } catch (Exception e) {
            log.error("Error checking ServiceNow change request approval status", e);
            throw new WorkflowException("Error checking ServiceNow change request approval status", e);
        }
    }
    
    private void handleCreatedState(String apiName, String apiVersion) throws WorkflowException {
        String apiTag = "api:" + apiName;
        String versionTag = "version:" + apiVersion;
        
        boolean changeRequestExists = checkIfChangeRequestExists(apiName, apiVersion, apiTag, versionTag);
        
        if (!changeRequestExists) {
            log.info("No existing change request found, creating new one");
            createServiceNowChangeRequest(apiName, apiVersion, apiTag, versionTag);
        } else {
            log.info("Change request already exists for API: " + apiName + " version: " + apiVersion);
        }
    }
    
    private boolean checkIfChangeRequestExists(String apiName, String apiVersion, String apiTag, String versionTag) 
            throws WorkflowException {
        
        try {
            // Find tags
            String apiTagSysId = findTagByName(apiTag);
            String versionTagSysId = findTagByName(versionTag);
            
            if (apiTagSysId == null || versionTagSysId == null) {
                log.error("[checkIfChangeRequestExists] Tags not found");
                return false;
            }
            
            // Query label_entry to find change_request with both tags
            String changeRequestSysId = findChangeRequestByTags(apiTagSysId, versionTagSysId);
            
            boolean exists = (changeRequestSysId != null);
            log.info("Change request exists for API: " + apiName + " version: " + apiVersion + " = " + exists);
            
            return exists;
            
        } catch (Exception e) {
            log.error("Error querying ServiceNow for existing change request", e);
            // If query fails, assume no change request exists and allow creation
            return false;
        }
    }
    
    private void createServiceNowChangeRequest(String apiName, String apiVersion, String apiTag, String versionTag) 
            throws WorkflowException {
        
        log.info("Creating ServiceNow change request for API: " + apiName + " version: " + apiVersion);
        
        try {
            // Step 1: Create the change request
            String changeSysId = createChangeRequestRecord(apiName, apiVersion);
            log.info("Created change request with sys_id: " + changeSysId);
            
            // Step 2: Get or create tags and attach them
            String[] labels = {apiTag, versionTag, "WSO2"};
            for (String labelName : labels) {
                String tagSysId = getOrCreateTag(labelName);
                if (tagSysId != null) {
                    attachTagToChange(tagSysId, changeSysId);
                    log.info("Attached tag '" + labelName + "' to change request");
                } else {
                    log.warn("Failed to get or create tag: " + labelName);
                }
            }
            
        } catch (Exception e) {
            String errorMsg = "Error creating ServiceNow change request";
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
    }
    
    private String createChangeRequestRecord(String apiName, String apiVersion) throws Exception {
        String serviceNowURL = serviceNowBaseUrl + "/api/now/table/change_request";
        
        URL serviceEndpointURL = new URL(serviceNowURL);
        HttpClient httpClient = APIUtil.getHttpClient(serviceEndpointURL.getPort(), serviceEndpointURL.getProtocol());
        HttpPost httpPost = new HttpPost(serviceNowURL);
        
        // Set ServiceNow API headers with Basic Authentication
        String auth = serviceNowUserName + ":" + serviceNowPassword;
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
        httpPost.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
        httpPost.setHeader(HttpHeaders.ACCEPT, "application/json");
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        
        // Create JSON payload
        String jsonPayload = String.format(
            "{\"short_description\":\"API %s version %s created\"," +
            "\"description\":\"API %s version %s has been created in WSO2 API Manager and requires approval for lifecycle progression.\"," +
            "\"category\":\"Software\"," +
            "\"type\":\"Normal\"," +
            "\"priority\":\"3\"," +
            "\"risk\":\"moderate\"," +
            "\"impact\":\"2\"," +
            "\"assignment_group\":\"Integration Platform\"}",
            escapeJson(apiName), escapeJson(apiVersion), escapeJson(apiName), escapeJson(apiVersion));
        
        httpPost.setEntity(new StringEntity(jsonPayload));
        
        HttpResponse response = httpClient.execute(httpPost);
        int statusCode = response.getStatusLine().getStatusCode();
        String responseBody = org.apache.http.util.EntityUtils.toString(response.getEntity());
        
        log.info("ServiceNow change request creation - Status: " + statusCode);
        
        if (statusCode == 201) {
            log.info("Successfully created ServiceNow change request for API: " + apiName);
            return extractFirstField(responseBody, "sys_id");
        } else {
            log.error("Failed to create ServiceNow change request. Status: " + statusCode + ", Response: " + responseBody);
            throw new WorkflowException("Failed to create ServiceNow change request");
        }
    }
    
    private String getOrCreateTag(String labelName) throws Exception {
        String existing = findTagByName(labelName);
        if (existing != null) {
            log.error("Found existing tag: " + labelName);
            return existing;
        }
        log.error("Creating new tag: " + labelName);
        return createTag(labelName);
    }
    
    private String findTagByName(String labelName) throws Exception {
        String query = "name=" + labelName + "^type=tag";
        String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        
        String queryURL = serviceNowBaseUrl + "/api/now/table/label?sysparm_query=" + encodedQuery + 
                         "&sysparm_fields=sys_id,name&sysparm_limit=1";
        
        URL queryEndpointURL = new URL(queryURL);
        HttpClient queryClient = APIUtil.getHttpClient(queryEndpointURL.getPort(), queryEndpointURL.getProtocol());
        HttpGet httpGet = new HttpGet(queryURL);
        
        String auth = serviceNowUserName + ":" + serviceNowPassword;
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
        httpGet.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
        httpGet.setHeader(HttpHeaders.ACCEPT, "application/json");
        
        HttpResponse response = queryClient.execute(httpGet);
        String responseBody = org.apache.http.util.EntityUtils.toString(response.getEntity());
        
        if (response.getStatusLine().getStatusCode() == 200) {
            if (!responseBody.contains("\"result\":[{")) {
                return null;
            }
            return extractFirstField(responseBody, "sys_id");
        }
        return null;
    }
    
    private String createTag(String labelName) throws Exception {
        String serviceNowURL = serviceNowBaseUrl + "/api/now/table/label";
        
        URL serviceEndpointURL = new URL(serviceNowURL);
        HttpClient httpClient = APIUtil.getHttpClient(serviceEndpointURL.getPort(), serviceEndpointURL.getProtocol());
        HttpPost httpPost = new HttpPost(serviceNowURL);
        
        String auth = serviceNowUserName + ":" + serviceNowPassword;
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
        httpPost.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
        httpPost.setHeader(HttpHeaders.ACCEPT, "application/json");
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        
        String jsonPayload = String.format(
            "{\"name\":\"%s\"," +
            "\"type\":\"tag\"," +
            "\"viewable_by\":\"everyone\"}",
            escapeJson(labelName));
        
        httpPost.setEntity(new StringEntity(jsonPayload));
        
        HttpResponse response = httpClient.execute(httpPost);
        int statusCode = response.getStatusLine().getStatusCode();
        String responseBody = org.apache.http.util.EntityUtils.toString(response.getEntity());
        
        if (statusCode == 201) {
            return extractFirstField(responseBody, "sys_id");
        } else {
            log.error("Failed to create tag. Status: " + statusCode + ", Response: " + responseBody);
            throw new WorkflowException("Failed to create tag: " + labelName);
        }
    }
    
    private void attachTagToChange(String tagSysId, String changeSysId) throws Exception {
        String serviceNowURL = serviceNowBaseUrl + "/api/now/table/label_entry";
        
        URL serviceEndpointURL = new URL(serviceNowURL);
        HttpClient httpClient = APIUtil.getHttpClient(serviceEndpointURL.getPort(), serviceEndpointURL.getProtocol());
        HttpPost httpPost = new HttpPost(serviceNowURL);
        
        String auth = serviceNowUserName + ":" + serviceNowPassword;
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
        httpPost.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodedAuth);
        httpPost.setHeader(HttpHeaders.ACCEPT, "application/json");
        httpPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        
        String jsonPayload = String.format(
            "{\"label\":\"%s\"," +
            "\"table\":\"change_request\"," +
            "\"table_key\":\"%s\"}",
            tagSysId, changeSysId);
        
        httpPost.setEntity(new StringEntity(jsonPayload));
        
        HttpResponse response = httpClient.execute(httpPost);
        int statusCode = response.getStatusLine().getStatusCode();
        
        if (statusCode != 201) {
            String responseBody = org.apache.http.util.EntityUtils.toString(response.getEntity());
            log.error("Failed to attach tag. Status: " + statusCode + ", Response: " + responseBody);
            throw new WorkflowException("Failed to attach tag to change request");
        }
    }
    
    private String extractFirstField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\"");
        if (idx == -1) return null;
        int colon = json.indexOf(":", idx);
        int quoteStart = json.indexOf("\"", colon + 1) + 1;
        int quoteEnd = json.indexOf("\"", quoteStart);
        if (quoteStart <= 0 || quoteEnd <= quoteStart) return null;
        return json.substring(quoteStart, quoteEnd);
    }
    
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

	public String getServiceNowBaseUrl() {
		return serviceNowBaseUrl;
	}

	public void setServiceNowBaseUrl(String serviceNowBaseUrl) {
		this.serviceNowBaseUrl = serviceNowBaseUrl;
	}

	public String getServiceNowUserName() {
		return serviceNowUserName;
	}

	public void setServiceNowUserName(String serviceNowUserName) {
		this.serviceNowUserName = serviceNowUserName;
	}

	public String getServiceNowPassword() {
		return serviceNowPassword;
	}

	public void setServiceNowPassword(String serviceNowPassword) {
		this.serviceNowPassword = serviceNowPassword;
	}
    
        public String getStateList() {
        return stateList;
    }

    public void setStateList(String stateList) {
        this.stateList = stateList;
    }

}
