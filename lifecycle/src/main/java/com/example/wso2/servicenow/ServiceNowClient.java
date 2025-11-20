package com.example.wso2.servicenow;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Client for ServiceNow REST API operations.
 * Handles HTTP communication and authentication.
 */
public class ServiceNowClient {

    private static final Log log = LogFactory.getLog(ServiceNowClient.class);

    private final String baseUrl;
    private final String username;
    private final String password;

    public ServiceNowClient(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Executes a GET request to ServiceNow API.
     * 
     * @param endpoint API endpoint path
     * @param queryParams Query parameters (already URL-encoded if needed)
     * @return Response body as JSON string
     * @throws Exception if request fails
     */
    public String executeGet(String endpoint, String queryParams) throws Exception {
        String url = baseUrl + endpoint;
        if (queryParams != null && !queryParams.isEmpty()) {
            url += "?" + queryParams;
        }

        URL endpointURL = new URL(baseUrl);
        HttpClient client = APIUtil.getHttpClient(endpointURL.getPort(), endpointURL.getProtocol());
        
        HttpGet get = new HttpGet(url);
        get.setHeader("Authorization", getAuthHeader());
        get.setHeader("Accept", "application/json");

        HttpResponse resp = client.execute(get);
        String json = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
        
        checkStatus(json, resp.getStatusLine().getStatusCode(), "GET " + endpoint);
        return json;
    }

    /**
     * Executes a POST request to ServiceNow API.
     * 
     * @param endpoint API endpoint path
     * @param jsonBody JSON request body
     * @return Response body as JSON string
     * @throws Exception if request fails
     */
    public String executePost(String endpoint, String jsonBody) throws Exception {
        String url = baseUrl + endpoint;

        URL endpointURL = new URL(baseUrl);
        HttpClient client = APIUtil.getHttpClient(endpointURL.getPort(), endpointURL.getProtocol());

        HttpPost post = new HttpPost(url);
        post.setHeader("Authorization", getAuthHeader());
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Accept", "application/json");

        post.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
        HttpResponse resp = client.execute(post);
        String json = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

        checkStatus(json, resp.getStatusLine().getStatusCode(), "POST " + endpoint);
        return json;
    }

    /**
     * Executes a PATCH request to ServiceNow API.
     * 
     * @param endpoint API endpoint path (including resource ID)
     * @param jsonBody JSON request body
     * @return Response body as JSON string
     * @throws Exception if request fails
     */
    public String executePatch(String endpoint, String jsonBody) throws Exception {
        String url = baseUrl + endpoint;

        URL endpointURL = new URL(baseUrl);
        HttpClient client = APIUtil.getHttpClient(endpointURL.getPort(), endpointURL.getProtocol());

        HttpPatch patch = new HttpPatch(url);
        patch.setHeader("Authorization", getAuthHeader());
        patch.setHeader("Content-Type", "application/json");
        patch.setHeader("Accept", "application/json");

        patch.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
        HttpResponse resp = client.execute(patch);
        String json = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);

        checkStatus(json, resp.getStatusLine().getStatusCode(), "PATCH " + endpoint);
        return json;
    }

    /**
     * Builds Basic Auth header for ServiceNow requests.
     * 
     * @return Authorization header value
     */
    private String getAuthHeader() {
        String auth = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    /**
     * Validates HTTP status codes for ServiceNow responses.
     * 
     * @param body Response body
     * @param status HTTP status code
     * @param context Operation description
     * @throws Exception if status indicates failure
     */
    private void checkStatus(String body, int status, String context) throws Exception {
        if (status >= 200 && status < 300) {
            return;
        }
        String errorMsg = "ServiceNow error during " + context + ". HTTP " + status + ": " + body;
        log.error(errorMsg);
        throw new Exception(errorMsg);
    }

    /**
     * URL-encodes a query string.
     * 
     * @param query Query string
     * @return URL-encoded query
     * @throws Exception if encoding fails
     */
    public static String encodeQuery(String query) throws Exception {
        return URLEncoder.encode(query, "UTF-8");
    }
}
