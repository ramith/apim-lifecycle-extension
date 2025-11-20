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

        log.error("[PromoteWorkflowExecutor] Processing workflow - API: '" + apiName + "' v" + apiVersion +
                ", Current State: '" + currentState + "', LC Action: '" + apiLifeCycleAction + "'");

        try {
            // Handle CREATED state - create change request if needed
            if ("CREATED".equals(currentState)) {
                log.error("[PromoteWorkflowExecutor] API in CREATED state, checking ServiceNow change request");
                handleCreatedState(apiName, apiVersion);
            }

            // Handle Publish/Re-Publish actions - validate approval
            if (apiLifeCycleAction != null &&
                    ("Publish".equalsIgnoreCase(apiLifeCycleAction.trim())
                            || "Re-Publish".equalsIgnoreCase(apiLifeCycleAction.trim()))) {
                log.error("[PromoteWorkflowExecutor] Publish action detected, validating ServiceNow approval");

                boolean isApproved = handlePublishAction(apiName, apiVersion, apiLifeCycleAction);

                if (!isApproved) {
                    log.error("[PromoteWorkflowExecutor] *** WORKFLOW REJECTED *** - No approval for API: '" + apiName
                            + "' v" + apiVersion);
                    workflowDTO.setStatus(WorkflowStatus.REJECTED);
                    WorkflowResponse response = complete(workflowDTO);
                    log.error("[PromoteWorkflowExecutor] ===== Exiting execute() with REJECTED status =====");
                    return response;
                }
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
     * 
     * @param apiName    API name
     * @param apiVersion API version
     * @param lcAction   Lifecycle action (Publish or Re-Publish)
     * @return true if authorized, false otherwise
     * @throws WorkflowException if validation fails
     */
    private boolean handlePublishAction(String apiName, String apiVersion, String lcAction) throws WorkflowException {

        log.error("[handlePublishAction] ----- Starting authorization validation -----");
        log.error("[handlePublishAction] API: '" + apiName + "' v" + apiVersion + ", Action: '" + lcAction + "'");

        String apiTag = "api:" + apiName;
        String versionTag = "version:" + apiVersion;

        log.error("[handlePublishAction] Tags: [" + apiTag + ", " + versionTag + "]");

        try {
            // Step 1: Find change request
            String changeRequestNumber = changeRequestManager.findChangeRequestNumber(apiTag, versionTag);

            if (changeRequestNumber == null) {
                log.error("[handlePublishAction] FAIL: No change request found for API: '" + apiName + "' v" + apiVersion);
                log.error("[handlePublishAction] Cannot proceed with " + lcAction + " - change request must exist");
                return false;
            }

            log.error("[handlePublishAction] Found change request: " + changeRequestNumber);

            // Step 2: Check if change request is in authorized state
            boolean isAuthorized = changeRequestManager.isChangeRequestAuthorized(apiTag, versionTag);

            if (!isAuthorized) {
                log.error("[handlePublishAction] FAIL: Change request " + changeRequestNumber + " is NOT in authorized state");
                log.error("[handlePublishAction] Rejecting " + lcAction + " action for API: '" + apiName + "' v" + apiVersion);

                // Step 3: Log unauthorized attempt
                changeRequestManager.addUnauthorizedAttemptComment(changeRequestNumber, apiName, apiVersion, lcAction);

                return false;
            }

            log.error("[handlePublishAction] SUCCESS: Change request " + changeRequestNumber + " is in AUTHORIZED state");
            log.error("[handlePublishAction] Proceeding with " + lcAction + " for API: '" + apiName + "' v" + apiVersion);
            log.error("[handlePublishAction] ----- Authorization validation complete -----");

            return true;

        } catch (Exception e) {
            String errorMsg = "[handlePublishAction] Error during authorization validation for API: '" + apiName + "' v" + apiVersion;
            log.error(errorMsg, e);
            throw new WorkflowException(errorMsg, e);
        }
    }

    /**
     * Handles CREATED state by creating a ServiceNow change request if one doesn't exist.
     * 
     * @param apiName    API name
     * @param apiVersion API version
     * @throws WorkflowException if operation fails
     */
    private void handleCreatedState(String apiName, String apiVersion) throws WorkflowException {

        log.error("[handleCreatedState] ----- Checking for existing change request -----");
        log.error("[handleCreatedState] API: '" + apiName + "' v" + apiVersion);

        String apiTag = "api:" + apiName;
        String versionTag = "version:" + apiVersion;

        try {
            boolean changeRequestExists = changeRequestManager.changeRequestExists(apiTag, versionTag);

            if (!changeRequestExists) {
                log.error("[handleCreatedState] No existing change request found - creating new one");
                changeRequestManager.createChangeRequestWithTags(apiName, apiVersion, apiTag, versionTag);
            } else {
                log.error("[handleCreatedState] Change request already exists - skipping creation");
            }

            log.error("[handleCreatedState] ----- CREATED state handling complete -----");

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
