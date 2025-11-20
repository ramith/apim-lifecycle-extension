# WSO2 API Manager ServiceNow Integration

This package provides ServiceNow change request integration for WSO2 API Manager lifecycle workflows.

## Architecture

The codebase is organized into a layered architecture for better maintainability:

### Main Workflow Executor
- **`PromoteWorkflowExecutor.java`** - Main orchestration class that:
  - Extends `APIStateChangeSimpleWorkflowExecutor`
  - Handles API lifecycle state changes
  - Delegates to service classes for ServiceNow operations
  - Only ~200 lines (reduced from 800+ lines)

### ServiceNow Service Layer (`servicenow/` package)

#### `ServiceNowClient.java`
Low-level HTTP client for ServiceNow REST API:
- Handles GET, POST, PATCH requests
- Manages authentication (Basic Auth)
- Validates HTTP status codes
- Uses WSO2's `APIUtil.getHttpClient()` for HTTP operations

#### `ServiceNowTagManager.java`
Manages ServiceNow tags (labels):
- `getOrCreateTag()` - Finds existing or creates new tags
- `findTagByName()` - Searches label table
- `createTag()` - Creates new label records
- `attachTagToChange()` - Links tags to change requests
- `buildTagQuery()` - Builds sys_tags queries for efficient multi-tag search

#### `ServiceNowChangeRequestManager.java`
Manages change request operations:
- `createChangeRequestWithTags()` - Creates CR with API tags
- `findChangeRequestNumber()` - Finds CR by tags using sys_tags reference field
- `changeRequestExists()` - Checks if CR exists
- `isChangeRequestAuthorized()` - Validates state=authorized
- `addUnauthorizedAttemptComment()` - Logs unauthorized publish attempts

### Utilities (`util/` package)

#### `JsonUtils.java`
Helper methods for JSON operations:
- `extractFirstField()` - Parses JSON responses
- `escapeJson()` - Escapes strings for JSON
- `hasResults()` - Checks for result presence
- `isEmpty()` - Checks for empty results

## Workflow Logic

### API Creation (CREATED state)
1. Check if change request already exists for API + version
2. If not, create new change request with tags:
   - `api:<apiName>`
   - `version:<apiVersion>`

### API Publish/Re-Publish
1. Find change request using sys_tags reference field query
2. Check if change request is in `state=authorized`
3. If authorized: Allow workflow to proceed
4. If not authorized: 
   - Reject workflow
   - Add comment to change request documenting the attempt

## ServiceNow Integration Details

### Tag-Based Search
Uses ServiceNow's `sys_tags` reference field for efficient multi-tag queries:
```
sys_tags.<tag1_sys_id>=<tag1_sys_id>^sys_tags.<tag2_sys_id>=<tag2_sys_id>
```
This approach requires only a single API call vs. multiple label_entry table queries.

### Authorization Check
Queries change_request table with combined filter:
- Both required tags (API name + version)
- `state=authorized`

## Configuration

Configure in `workflow-extensions.xml`:
```xml
<WorkflowExtensions>
    <APIStateChange executor="com.example.wso2.PromoteWorkflowExecutor">
        <Property name="serviceNowBaseUrl">https://your-instance.service-now.com</Property>
        <Property name="serviceNowUserName">your-username</Property>
        <Property name="serviceNowPassword">your-password</Property>
    </APIStateChange>
</WorkflowExtensions>
```

## Benefits of Refactoring

1. **Separation of Concerns**: Each class has a single, well-defined responsibility
2. **Testability**: Service classes can be unit tested independently
3. **Maintainability**: Changes to ServiceNow API logic don't affect workflow orchestration
4. **Reusability**: Service classes can be reused for other integrations
5. **Readability**: Smaller, focused classes are easier to understand and navigate
6. **Extensibility**: Easy to add new ServiceNow operations or modify existing ones
