package com.example.wso2.mapping;

/**
 * Data Transfer Object for API-to-ChangeRequest mapping.
 */
public class MappingDTO {
    private String sysId;
    private String apiId;
    private String apiName;
    private String apiVersion;

    public MappingDTO() {
    }

    public MappingDTO(String sysId, String apiId, String apiName, String apiVersion) {
        this.sysId = sysId;
        this.apiId = apiId;
        this.apiName = apiName;
        this.apiVersion = apiVersion;
    }

    public String getSysId() {
        return sysId;
    }

    public void setSysId(String sysId) {
        this.sysId = sysId;
    }

    public String getApiId() {
        return apiId;
    }

    public void setApiId(String apiId) {
        this.apiId = apiId;
    }

    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    /**
     * Converts DTO to JSON object for API requests.
     */
    public org.json.simple.JSONObject toJSONObject() {
        org.json.simple.JSONObject json = new org.json.simple.JSONObject();
        json.put("sys_id", sysId);
        json.put("api_id", apiId);
        json.put("api_name", apiName);
        json.put("api_version", apiVersion);
        return json;
    }

    /**
     * Creates DTO from JSON object received from API.
     */
    public static MappingDTO fromJSONObject(org.json.simple.JSONObject json) {
        if (json == null) {
            return null;
        }
        MappingDTO dto = new MappingDTO();
        dto.setSysId((String) json.get("sys_id"));
        dto.setApiId((String) json.get("api_id"));
        dto.setApiName((String) json.get("api_name"));
        dto.setApiVersion((String) json.get("api_version"));
        return dto;
    }

    @Override
    public String toString() {
        return "MappingDTO{" +
                "sysId='" + sysId + '\'' +
                ", apiId='" + apiId + '\'' +
                ", apiName='" + apiName + '\'' +
                ", apiVersion='" + apiVersion + '\'' +
                '}';
    }
}
