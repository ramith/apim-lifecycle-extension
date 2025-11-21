package com.example.wso2.mapping;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client for the ServiceNow Mapping Service API.
 * Handles HTTP communication with the mapping service.
 */
public class MappingServiceClient {

    private static final Log log = LogFactory.getLog(MappingServiceClient.class);
    
    private final String baseUrl;
    private final String apiKey;
    private final JSONParser parser = new JSONParser();

    public MappingServiceClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        log.info("[MappingServiceClient] Initialized with base URL: " + this.baseUrl);
    }

    /**
     * Get mapping by API ID.
     * GET /mappings/api-id/{api_id}
     */
    public MappingDTO getMappingByApiId(String apiId) throws Exception {
        String endpoint = baseUrl + "/mappings/api-id/" + apiId;
        JSONObject json = doGet(endpoint);
        return MappingDTO.fromJSONObject(json);
    }

    /**
     * Create a new mapping.
     * POST /mappings
     */
    public MappingDTO createMapping(String sysId, String apiId, String apiName, String apiVersion) throws Exception {
        String endpoint = baseUrl + "/mappings";
        
        MappingDTO mapping = new MappingDTO(sysId, apiId, apiName, apiVersion);
        JSONObject payload = mapping.toJSONObject();
        
        JSONObject response = doPost(endpoint, payload);
        return MappingDTO.fromJSONObject(response);
    }

    /**
     * Update an existing mapping by API ID.
     * PUT /mappings/api-id/{api_id}
     */
    public MappingDTO updateMapping(String apiId, String sysId, String apiName, String apiVersion) throws Exception {
        String endpoint = baseUrl + "/mappings/api-id/" + apiId;
        
        JSONObject payload = new JSONObject();
        payload.put("sys_id", sysId);
        payload.put("api_name", apiName);
        payload.put("api_version", apiVersion);
        
        JSONObject response = doPut(endpoint, payload);
        return MappingDTO.fromJSONObject(response);
    }

    /**
     * Executes HTTP GET request.
     */
    private JSONObject doGet(String endpoint) throws Exception {
        log.info("[MappingServiceClient] GET " + endpoint);
        
        URL endpointURL = new URL(endpoint);
        HttpClient client = APIUtil.getHttpClient(endpointURL.getPort(), endpointURL.getProtocol());
        
        HttpGet get = new HttpGet(endpoint);
        get.setHeader("Content-Type", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            get.setHeader("api-key", apiKey);
        }

        HttpResponse resp = client.execute(get);
        int responseCode = resp.getStatusLine().getStatusCode();
        log.info("[MappingServiceClient] Response code: " + responseCode);

        if (responseCode == 404) {
            // Not found is expected - return null
            return null;
        }

        if (responseCode != 200) {
            String errorMsg = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            throw new Exception("HTTP GET failed: " + responseCode + " - " + errorMsg);
        }

        String responseBody = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
        return (JSONObject) parser.parse(responseBody);
    }

    /**
     * Executes HTTP POST request.
     */
    private JSONObject doPost(String endpoint, JSONObject payload) throws Exception {
        log.info("[MappingServiceClient] POST " + endpoint);
        log.info("[MappingServiceClient] Payload: " + payload.toJSONString());
        
        URL endpointURL = new URL(endpoint);
        HttpClient client = APIUtil.getHttpClient(endpointURL.getPort(), endpointURL.getProtocol());
        
        HttpPost post = new HttpPost(endpoint);
        post.setHeader("Content-Type", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            post.setHeader("api-key", apiKey);
        }
        
        StringEntity entity = new StringEntity(payload.toJSONString(), StandardCharsets.UTF_8);
        post.setEntity(entity);

        HttpResponse resp = client.execute(post);
        int responseCode = resp.getStatusLine().getStatusCode();
        log.info("[MappingServiceClient] Response code: " + responseCode);

        if (responseCode != 201 && responseCode != 200) {
            String errorMsg = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            throw new Exception("HTTP POST failed: " + responseCode + " - " + errorMsg);
        }

        String responseBody = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
        return (JSONObject) parser.parse(responseBody);
    }

    /**
     * Executes HTTP PUT request.
     */
    private JSONObject doPut(String endpoint, JSONObject payload) throws Exception {
        log.info("[MappingServiceClient] PUT " + endpoint);
        log.info("[MappingServiceClient] Payload: " + payload.toJSONString());
        
        URL endpointURL = new URL(endpoint);
        HttpClient client = APIUtil.getHttpClient(endpointURL.getPort(), endpointURL.getProtocol());
        
        org.apache.http.client.methods.HttpPut put = new org.apache.http.client.methods.HttpPut(endpoint);
        put.setHeader("Content-Type", "application/json");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            put.setHeader("api-key", apiKey);
        }
        
        StringEntity entity = new StringEntity(payload.toJSONString(), StandardCharsets.UTF_8);
        put.setEntity(entity);

        HttpResponse resp = client.execute(put);
        int responseCode = resp.getStatusLine().getStatusCode();
        log.info("[MappingServiceClient] Response code: " + responseCode);

        if (responseCode != 200) {
            String errorMsg = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            throw new Exception("HTTP PUT failed: " + responseCode + " - " + errorMsg);
        }

        String responseBody = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
        return (JSONObject) parser.parse(responseBody);
    }
}
