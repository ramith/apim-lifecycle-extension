# Major Refactoring: Mapping Service Integration

## Overview
Simplified the workflow executor by introducing a dedicated mapping service to manage the relationship between ServiceNow change request sys_ids and WSO2 API Manager APIs. This eliminates the need for ServiceNow tag/label management and WSO2 properties-based caching.

## Changes Made

### 1. New Component: Mapping Service Client
**File**: `lifecycle/src/main/java/com/example/wso2/mapping/MappingServiceClient.java`

- HTTP client for the ServiceNow Mapping Service API
- Methods:
  - `getMappingByApiId(String apiId)` - Lookup by API UUID
  - `searchMappingByNameAndVersion(String apiName, String apiVersion)` - Search by name/version
  - `getMappingBySysId(String sysId)` - Lookup by ServiceNow sys_id
  - `createMapping(String sysId, String apiId, String apiName, String apiVersion)` - Create new mapping

### 2. Refactored: PromoteWorkflowExecutor
**File**: `lifecycle/src/main/java/com/example/wso2/PromoteWorkflowExecutor.java`

#### Removed:
- `PROPERTY_SERVICENOW_SYS_ID` constant (no more caching in WSO2)
- `ServiceNowTagManager` dependency (no more tag/label operations)
- Properties/metadata caching logic
- Tag search logic

#### Added:
- `MappingServiceClient` instance
- API ID extraction from `WorkflowDTO.getWorkflowReference()`

#### Simplified Methods:

**`handleCreatedState(String apiId, String apiName, String apiVersion)`**:
```java
// Before: 40+ lines with caching, tag creation, tag search
// After: 20 lines - check mapping exists, create CR, store mapping
```

**`handlePublishAction(String apiId, String apiName, String apiVersion, String lcAction)`**:
```java
// Before: 90+ lines with cache checks, tag searches, fallback logic
// After: 35 lines - lookup mapping by API ID, get CR details, validate state
```

### 3. Enhanced: ServiceNowChangeRequestManager
**File**: `lifecycle/src/main/java/com/example/wso2/servicenow/ServiceNowChangeRequestManager.java`

#### New Methods:
- `getChangeRequestBySysId(String sysId)` - Get complete CR details by sys_id
- `isStateAuthorized(String state)` - Check if state is approved (states: 1, 2, 3, "authorized", "approved")

## Architecture Benefits

### Before:
```
WSO2 API Manager
├── Workflow Executor
│   ├── Create tags in ServiceNow (api:name, version:X.Y)
│   ├── Cache sys_id in WorkflowDTO properties
│   ├── Search by tags on each publish
│   └── Fallback to cache if tags fail
```

### After:
```
WSO2 API Manager          Mapping Service          ServiceNow
├── Workflow Executor --> ├── GET /mappings/api-id/{id} --> ├── Change Requests
│   ├── Get API ID        ├── POST /mappings             └── (simplified)
│   ├── Lookup mapping    └── Database (mapping store)
│   └── Validate CR state
```

## API Call Reduction

### CREATED State:
- **Before**: 3 API calls (search tags, create tags, create CR)
- **After**: 2 calls (lookup mapping → Mapping Service, create CR → ServiceNow)

### PUBLISH State:
- **Before**: 3-9 calls depending on cache hits (tag search, get CR details, validate)
- **After**: 2 calls (get mapping → Mapping Service, get CR → ServiceNow)

**Total Reduction**: ~60% fewer ServiceNow API calls, eliminated tag/label operations

## Configuration

### New Required Properties in `workflow-extensions.xml`:
```xml
<Property name="mappingServiceUrl">http://localhost:8080</Property>
<Property name="mappingServiceApiKey">your-api-key</Property>
```

### Mapping Service API Endpoints Used:
- `GET /mappings/api-id/{api_id}` - Primary lookup method
- `POST /mappings` - Create new mapping
- `GET /health` - Health check (optional)

## Data Flow

### 1. API Created (CREATED State):
```
1. User creates API in WSO2 APIM
2. WorkflowExecutor extracts apiId (UUID from WorkflowDTO)
3. Check if mapping exists: GET /mappings/api-id/{apiId}
4. If not exists:
   a. Create ServiceNow Change Request
   b. Create mapping: POST /mappings {sys_id, api_id, api_name, api_version}
```

### 2. API Published (Publish Action):
```
1. User publishes API
2. WorkflowExecutor extracts apiId
3. Get mapping: GET /mappings/api-id/{apiId}
4. Get CR details from ServiceNow using sys_id
5. Validate CR state is authorized
6. Approve or reject workflow
```

## Key Improvements

1. **Separation of Concerns**: Mapping logic moved to dedicated microservice
2. **Simplified Code**: 50% reduction in executor code complexity
3. **Better Performance**: Fewer API calls, no fallback logic
4. **Maintainability**: No tag management in ServiceNow
5. **Scalability**: Mapping service can be scaled independently
6. **Reliability**: Single source of truth for API-to-CR relationships

## Testing Checklist

- [ ] Mapping service is running and accessible
- [ ] API creation triggers mapping creation
- [ ] Duplicate API creation doesn't create duplicate mappings
- [ ] Publish action finds correct mapping by API ID
- [ ] Publish blocked when CR not in authorized state
- [ ] Publish succeeds when CR is authorized
- [ ] Error handling for mapping service unavailability
- [ ] Verify no ServiceNow tag/label operations occur

## Migration Notes

**Existing Deployments**:
- Old workflow data (cached in properties) will be ignored
- First publish after upgrade will use mapping service
- No data migration needed - mappings created on-demand
- ServiceNow tags can be manually cleaned up (optional)

**Rollback Plan**:
- Revert to previous JAR
- Mapping service data preserved for future use
- No WSO2 configuration changes needed
