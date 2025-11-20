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
import com.example.wso2.servicenow.ServiceNowTagManager;

/**
 * Workflow executor for API state changes with ServiceNow integration.
 * Handles automatic change request creation and approval validation.
 * 
 * This class orchestrates the workflow by delegating to specialized service classes:
 * - ServiceNowClient: HTTP communication with ServiceNow API
 * - ServiceNowTagManager: Tag/label operations
 * - ServiceNowChangeRequestManager: Change request operations
 */
public class PromoteWorkflowExecutor extends APIStateChangeSimpleWorkflowExecutor {

    private static final long serialVersionUID = 1L;
    private static final Log log = LogFactory.getLog(PromoteWorkflowExecutor.class);
    
    // Properties key for caching ServiceNow change request sys_id
    private static final String PROPERTY_SERVICENOW_SYS_ID = "servicenow_change_sys_id";

    // ServiceNow configuration - initialized from workflow-extensions.xml
    private String serviceNowBaseUrl;
    private String serviceNowUserName;
    private String serviceNowPassword;
    private String stateList;

    // ServiceNow service components
    private ServiceNowClient serviceNowClient;
    private ServiceNowTagManager tagManager;
    private ServiceNowChangeRequestManager changeRequestManager;

    /**
     * Initializes ServiceNow service components.
     * Called lazily on first use to ensure configuration is loaded.
     */
    private void initializeServices() {
        if (serviceNowClient == null) {
            serviceNowClient = new ServiceNowClient(serviceNowBaseUrl, serviceNowUserName, serviceNowPassword);
            tagManager = new ServiceNowTagManager(serviceNowClient);
            changeRequestManager = new ServiceNowChangeRequestManager(serviceNowClient, tagManager);
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
        Object apiMetadata = apiStateWorkFlowDTO.getMetadata();
        Object apiProperties = apiStateWorkFlowDTO.getProperties();

        log.error("[PromoteWorkflowExecutor] Processing workflow - API: '" + apiName + "' v" + apiVersion +
                ", Current State: '" + currentState + "', LC Action: '" + apiLifeCycleAction + "'");
        log.error("[PromoteWorkflowExecutor] Metadata: " + apiMetadata.toString());
        log.error("[PromoteWorkflowExecutor] Properties: " + apiProperties.toString());

        try {
            // Handle CREATED state - create change request if needed
            if ("CREATED".equals(currentState)) {
                log.error("[PromoteWorkflowExecutor] API in CREATED state, checking ServiceNow change request");
                handleCreatedState(apiStateWorkFlowDTO, apiName, apiVersion);
            }

            // Handle Publish/Re-Publish actions - validate approval
            if (apiLifeCycleAction != null &&
                    ("Publish".equalsIgnoreCase(apiLifeCycleAction.trim())
                            || "Re-Publish".equalsIgnoreCase(apiLifeCycleAction.trim()))) {
                log.error("[PromoteWorkflowExecutor] Publish action detected, validating ServiceNow approval");

                // handlePublishAction throws WorkflowException if validation fails - this blocks the publish
                handlePublishAction(apiStateWorkFlowDTO, apiName, apiVersion, apiLifeCycleAction);
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
     * Throws WorkflowException to block the publish if validation fails.
     * Uses metadata caching to avoid redundant tag searches.
     * 
     * @param workflowDTO WorkflowDTO containing metadata for caching
     * @param apiName    API name
     * @param apiVersion API version
     * @param lcAction   Lifecycle action (Publish or Re-Publish)
     * @throws WorkflowException if validation fails or change request not found/not authorized
     */
    private void handlePublishAction(APIStateWorkflowDTO workflowDTO, String apiName, String apiVersion, String lcAction) throws WorkflowException {

        log.error("[handlePublishAction] ----- Starting authorization validation -----");
        log.error("[handlePublishAction] API: '" + apiName + "' v" + apiVersion + ", Action: '" + lcAction + "'");

        String apiTag = "api:" + apiName;
        String versionTag = "version:" + apiVersion;

        log.error("[handlePublishAction] Tags: [" + apiTag + ", " + versionTag + "]");

        try {
            // Step 1: Check for cached sys_id in properties
            String cachedSysId = workflowDTO.getProperties(PROPERTY_SERVICENOW_SYS_ID);
            if (cachedSysId != null && !cachedSysId.trim().isEmpty()) {
                log.error("[handlePublishAction] Found cached change request sys_id: " + cachedSysId);
            } else {
                log.error("[handlePublishAction] No cached sys_id found in properties");
            }

            // Step 2: Check authorization (using cache if available)
            String changeRequestNumber;
            boolean isAuthorized;
            
            if (cachedSysId != null && !cachedSysId.trim().isEmpty()) {
                // Use cached sys_id to check authorization directly (1 API call)
                String[] result = changeRequestManager.checkAuthorizationBySysId(cachedSysId);
                if (result != null) {
                    changeRequestNumber = result[0];
                    isAuthorized = "true".equals(result[1]);
                    log.error("[handlePublishAction] Retrieved from cache - CR: " + changeRequestNumber + ", Authorized: " + isAuthorized);
                } else {
                    log.error("[handlePublishAction] Cached sys_id invalid, falling back to tag search");
                    changeRequestNumber = changeRequestManager.findChangeRequestNumber(apiTag, versionTag);
                    if (changeRequestNumber == null) {
                        String errorMsg = "[handlePublishAction] REJECT: No ServiceNow change request found for API: '" + apiName + "' v" + apiVersion + ". Cannot proceed with " + lcAction + ".";
                        log.error(errorMsg);
                        throw new WorkflowException(errorMsg);
                    }
                    isAuthorized = changeRequestManager.isChangeRequestAuthorized(apiTag, versionTag);
                }
            } else {
                // No cache - search by tags and check authorization
                changeRequestNumber = changeRequestManager.findChangeRequestNumber(apiTag, versionTag);
                if (changeRequestNumber == null) {
                    String errorMsg = "[handlePublishAction] REJECT: No ServiceNow change request found for API: '" + apiName + "' v" + apiVersion + ". Cannot proceed with " + lcAction + ".";
                    log.error(errorMsg);
                    throw new WorkflowException(errorMsg);
                }
                isAuthorized = changeRequestManager.isChangeRequestAuthorized(apiTag, versionTag);
            }

            // Step 3: Validate authorization status

            if (!isAuthorized) {
                String errorMsg = "[handlePublishAction] REJECT: Change request '" + changeRequestNumber
                        + "' is NOT in authorized state for API: '" + apiName + "' v" + apiVersion
                        + ". Cannot proceed with " + lcAction + " - change request must be in an approved state.";
                log.error(errorMsg);

                // Log unauthorized attempt
                changeRequestManager.addUnauthorizedAttemptComment(changeRequestNumber, apiName, apiVersion, lcAction);

                throw new WorkflowException(errorMsg);
            }

            log.error("[handlePublishAction] SUCCESS: Change request '" + changeRequestNumber
                    + "' is authorized for API: '" + apiName + "' v" + apiVersion);
            log.error("[handlePublishAction] ----- Authorization validation complete -----");

        } catch (WorkflowException we) {
            // Re-throw WorkflowException to block the publish
            throw we;
        } catch (Exception e) {
            String errorMsg = "[handlePublishAction] Error during authorization validation for API: '" + apiName + "' v" + apiVersion;
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
    }

    /**
     * Handles CREATED state by creating a ServiceNow change request if one doesn't exist.
     * Caches the change request sys_id in workflow metadata for future use.
     * 
     * @param workflowDTO WorkflowDTO to store metadata
     * @param apiName    API name
     * @param apiVersion API version
     * @throws WorkflowException if operation fails
     */
    private void handleCreatedState(APIStateWorkflowDTO workflowDTO, String apiName, String apiVersion) throws WorkflowException {

        log.error("[handleCreatedState] ----- Checking for existing change request -----");
        log.error("[handleCreatedState] API: '" + apiName + "' v" + apiVersion);

        String apiTag = "api:" + apiName;
        String versionTag = "version:" + apiVersion;

        try {
            // Check if sys_id is already cached and valid
            String cachedSysId = workflowDTO.getProperties(PROPERTY_SERVICENOW_SYS_ID);
            if (cachedSysId != null && !cachedSysId.trim().isEmpty() 
                    && changeRequestManager.changeRequestExistsBySysId(cachedSysId)) {
                log.error("[handleCreatedState] Cached change request verified: " + cachedSysId);
                return;
            }

            // Create new change request and cache it
            String changeSysId = changeRequestManager.createChangeRequestWithTags(apiName, apiVersion, apiTag, versionTag);
            workflowDTO.setProperties(PROPERTY_SERVICENOW_SYS_ID, changeSysId);
            log.error("[handleCreatedState] Created and cached change request: " + changeSysId);

        } catch (Exception e) {
            String errorMsg = "[handleCreatedState] Error handling CREATED state for API: '" + apiName + "' v" + apiVersion;
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
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

    public String getStateList() {
        return stateList;
    }

    public void setStateList(String stateList) {
        this.stateList = stateList;
        log.error("[Config] State list configured");
    }
}
