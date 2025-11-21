package com.example.wso2;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.WorkflowResponse;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.workflow.APIStateChangeSimpleWorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.APIStateWorkflowDTO;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;

import com.example.wso2.servicenow.ServiceNowChangeRequestManager;
import com.example.wso2.servicenow.ServiceNowClient;
import com.example.wso2.mapping.MappingServiceClient;

/**
 * Workflow executor for API state changes with ServiceNow integration.
 * Handles automatic change request creation and approval validation.
 * 
 * This class orchestrates the workflow by delegating to specialized service classes:
 * - ServiceNowClient: HTTP communication with ServiceNow API
 * - ServiceNowChangeRequestManager: Change request operations
 * - MappingServiceClient: API-to-ChangeRequest mapping persistence
 */
public class PromoteWorkflowExecutor extends APIStateChangeSimpleWorkflowExecutor {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(PromoteWorkflowExecutor.class);

    // ServiceNow configuration - initialized from workflow-extensions.xml
    private String serviceNowBaseUrl;
    private String serviceNowUserName;
    private String serviceNowPassword;
    private String mappingServiceUrl;
    private String mappingServiceApiKey;

    // Service components
    private ServiceNowClient serviceNowClient;
    private ServiceNowChangeRequestManager changeRequestManager;
    private MappingServiceClient mappingServiceClient;

    /**
     * Initializes service components.
     * Called lazily on first use to ensure configuration is loaded.
     */
    private void initializeServices() {
        if (serviceNowClient == null) {
            serviceNowClient = new ServiceNowClient(serviceNowBaseUrl, serviceNowUserName, serviceNowPassword);
            changeRequestManager = new ServiceNowChangeRequestManager(serviceNowClient);
            mappingServiceClient = new MappingServiceClient(mappingServiceUrl, mappingServiceApiKey);
        }
    }

    @Override
    public WorkflowResponse execute(WorkflowDTO workflowDTO) throws WorkflowException {

        log.error("[PromoteWorkflowExecutor] ===== Starting execute() method =====");

        if (workflowDTO == null) {
            String errorMsg = "[PromoteWorkflowExecutor] WorkflowDTO is null";
            log.error(errorMsg);
            throw new WorkflowException(errorMsg);
        }

        // Initialize ServiceNow services
        initializeServices();

        APIStateWorkflowDTO apiStateWorkFlowDTO = (APIStateWorkflowDTO) workflowDTO;

        String currentState = apiStateWorkFlowDTO.getApiCurrentState();
        String apiName = apiStateWorkFlowDTO.getApiName();
        String apiVersion = apiStateWorkFlowDTO.getApiVersion();
        String apiLifeCycleAction = apiStateWorkFlowDTO.getApiLCAction();
        String apiId = apiStateWorkFlowDTO.getApiUUID();

        log.error("[PromoteWorkflowExecutor] Processing workflow - API: '" + apiName + "' v" + apiVersion +
                " (ID: " + apiId + "), Current State: '" + currentState + "', LC Action: '" + apiLifeCycleAction + "'");

        try {
            // Handle CREATED state - create change request if needed
            if ("CREATED".equals(currentState)) {
                log.error("[PromoteWorkflowExecutor] API in CREATED state, creating ServiceNow change request");
                handleCreatedState(apiId, apiName, apiVersion);
            }

            // Handle Publish/Re-Publish actions - validate approval
            if (apiLifeCycleAction != null &&
                    ("Publish".equalsIgnoreCase(apiLifeCycleAction.trim())
                            || "Re-Publish".equalsIgnoreCase(apiLifeCycleAction.trim()))) {
                log.error("[PromoteWorkflowExecutor] Publish action detected, validating ServiceNow approval");

                // handlePublishAction throws WorkflowException if validation fails - this blocks the publish
                handlePublishAction(apiId, apiName, apiVersion, apiLifeCycleAction);
            }

            // All checks passed - approve workflow
            log.error("[PromoteWorkflowExecutor] *** WORKFLOW APPROVED *** for API: '" + apiName + "' v" + apiVersion);
            workflowDTO.setStatus(WorkflowStatus.APPROVED);
            WorkflowResponse response = complete(workflowDTO);

            log.error("[PromoteWorkflowExecutor] ===== Exiting execute() with APPROVED status =====");
            return response;

        } catch (WorkflowException we) {
            log.error("[PromoteWorkflowExecutor] WorkflowException in execute() for API: '" + apiName + "' v"
                    + apiVersion, we);
            throw we;
        } catch (Exception e) {
            String errorMsg = "[PromoteWorkflowExecutor] Unexpected exception in execute() for API: '" + apiName + "' v"
                    + apiVersion;
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
    }

    /**
     * Handles publish/re-publish actions by validating ServiceNow change request is authorized.
     * Uses mapping service to find the change request sys_id.
     * Throws WorkflowException to block the publish if validation fails.
     * 
     * @param apiId      API UUID
     * @param apiName    API name
     * @param apiVersion API version
     * @param lcAction   Lifecycle action (Publish or Re-Publish)
     * @throws WorkflowException if validation fails or change request not found/not authorized
     */
    private void handlePublishAction(String apiId, String apiName, String apiVersion, String lcAction) 
            throws WorkflowException {

        log.error("[handlePublishAction] ----- Starting authorization validation -----");
        log.error("[handlePublishAction] API: '" + apiName + "' v" + apiVersion + " (ID: " + apiId + ")");

        try {
            // Step 1: Get sys_id from mapping service
            com.example.wso2.mapping.MappingDTO mapping = mappingServiceClient.getMappingByApiId(apiId);
            
            if (mapping == null) {
                String errorMsg = "[handlePublishAction] REJECT: No mapping found for API '" + apiName + "' v" 
                    + apiVersion + ". Cannot proceed with " + lcAction + ".";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg);
            }

            String sysId = mapping.getSysId();
            log.error("[handlePublishAction] Found sys_id from mapping: " + sysId);

            // Step 2: Get change request details from ServiceNow
            org.json.simple.JSONObject crDetails = changeRequestManager.getChangeRequestBySysId(sysId);
            
            if (crDetails == null) {
                String errorMsg = "[handlePublishAction] REJECT: Change request " + sysId 
                    + " not found in ServiceNow for API '" + apiName + "' v" + apiVersion + ".";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg);
            }

            String changeRequestNumber = (String) crDetails.get("number");
            String state = (String) crDetails.get("state");
            
            // Step 3: Check if change request is in authorized state
            boolean isAuthorized = changeRequestManager.isStateAuthorized(state);

            if (!isAuthorized) {
                String errorMsg = "[handlePublishAction] REJECT: Change request '" + changeRequestNumber
                        + "' (state: " + state + ") is NOT in authorized state for API: '" + apiName + "' v" + apiVersion
                        + ". Cannot proceed with " + lcAction + " - change request must be in an approved state.";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg);
            }

            log.error("[handlePublishAction] SUCCESS: Change request '" + changeRequestNumber
                    + "' is authorized for API: '" + apiName + "' v" + apiVersion);
            log.error("[handlePublishAction] ----- Authorization validation complete -----");

        } catch (WorkflowException we) {
            throw we;
        } catch (Exception e) {
            String errorMsg = "[handlePublishAction] Error during authorization validation for API: '" + apiName 
                + "' v" + apiVersion;
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
    }

    /**
     * Handles CREATED state by ensuring ServiceNow change request and mapping exist.
     * Creates or repairs the CR/mapping relationship as needed.
     * 
     * @param apiId      API UUID
     * @param apiName    API name
     * @param apiVersion API version
     * @throws WorkflowException if operation fails
     */
    private void handleCreatedState(String apiId, String apiName, String apiVersion) throws WorkflowException {

        log.error("[handleCreatedState] Processing API: '" + apiName + "' v" + apiVersion + " (ID: " + apiId + ")");

        try {
            com.example.wso2.mapping.MappingDTO mapping = mappingServiceClient.getMappingByApiId(apiId);
            
            if (mapping == null) {
                // Fresh API - create everything
                createNewChangeRequestAndMapping(apiId, apiName, apiVersion);
                return;
            }

            // Mapping exists - verify CR still exists in ServiceNow
            if (changeRequestExistsInServiceNow(mapping.getSysId())) {
                log.error("[handleCreatedState] Change request already exists, nothing to do");
                return;
            }

            // CR missing - recreate and update mapping
            repairChangeRequestMapping(apiId, apiName, apiVersion, mapping.getSysId());

        } catch (Exception e) {
            String errorMsg = "[handleCreatedState] Error handling CREATED state for API: '" + apiName + "' v" + apiVersion;
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
    }

    /**
     * Creates new change request in ServiceNow and stores mapping.
     */
    private void createNewChangeRequestAndMapping(String apiId, String apiName, String apiVersion) throws Exception {
        log.error("[handleCreatedState] No mapping found - creating new CR and mapping");
        
        String sysId = changeRequestManager.createChangeRequest(apiName, apiVersion);
        log.error("[handleCreatedState] Created change request: " + sysId);
        
        mappingServiceClient.createMapping(sysId, apiId, apiName, apiVersion);
        log.error("[handleCreatedState] Created mapping");
    }

    /**
     * Checks if change request exists in ServiceNow.
     */
    private boolean changeRequestExistsInServiceNow(String sysId) throws Exception {
        log.error("[handleCreatedState] Verifying CR exists: " + sysId);
        org.json.simple.JSONObject crDetails = changeRequestManager.getChangeRequestBySysId(sysId);
        return crDetails != null;
    }

    /**
     * Recreates missing change request and updates the mapping.
     */
    private void repairChangeRequestMapping(String apiId, String apiName, String apiVersion, String oldSysId) throws Exception {
        log.error("[handleCreatedState] WARNING: CR " + oldSysId + " missing, recreating");
        
        String newSysId = changeRequestManager.createChangeRequest(apiName, apiVersion);
        log.error("[handleCreatedState] Created new change request: " + newSysId);
        
        mappingServiceClient.updateMapping(apiId, newSysId, apiName, apiVersion);
        log.error("[handleCreatedState] Updated mapping with new sys_id");
    }

    public String getServiceNowBaseUrl() {
        return serviceNowBaseUrl;
    }

    public void setServiceNowBaseUrl(String serviceNowBaseUrl) {
        this.serviceNowBaseUrl = serviceNowBaseUrl;
        log.error("[Config] ServiceNow Base URL configured");
    }

    public String getServiceNowUserName() {
        return serviceNowUserName;
    }

    public void setServiceNowUserName(String serviceNowUserName) {
        this.serviceNowUserName = serviceNowUserName;
        log.error("[Config] ServiceNow Username configured");
    }

    public String getServiceNowPassword() {
        return serviceNowPassword;
    }

    public void setServiceNowPassword(String serviceNowPassword) {
        this.serviceNowPassword = serviceNowPassword;
        log.error("[Config] ServiceNow Password configured (hidden)");
    }

    public String getMappingServiceUrl() {
        return mappingServiceUrl;
    }

    public void setMappingServiceUrl(String mappingServiceUrl) {
        this.mappingServiceUrl = mappingServiceUrl;
    }

    public String getMappingServiceApiKey() {
        return mappingServiceApiKey;
    }

    public void setMappingServiceApiKey(String mappingServiceApiKey) {
        this.mappingServiceApiKey = mappingServiceApiKey;
    }
}
