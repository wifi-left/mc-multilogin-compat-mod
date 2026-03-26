package net.wifil.mcmultilogin.api;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin HTTP client that calls the MC-MultiLogin-service
 * {@code /session/minecraft/hasJoined} endpoint with {@code detail=true}.
 */
public class LoginApiClient {

    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String apiBase;
    private final HttpClient httpClient;

    public LoginApiClient(String apiBase) {
        // Strip trailing slash for consistent URL construction
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * Queries {@code hasJoined} with {@code detail=true}.
     *
     * @return {@link ApiResult} whose {@link ApiResult#statusCode()} is the HTTP status.
     *         On HTTP 200 the body is the raw GameProfile JSON.
     *         On HTTP 403 the body is the {@link ErrorResponse} JSON.
     * @throws IOException          on network error
     * @throws InterruptedException if the thread is interrupted
     */
    public ApiResult hasJoined(String username, String serverId, InetAddress address,
                               boolean detail) throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder(apiBase)
                .append("/sessionserver/session/minecraft/hasJoined?username=")
                .append(URLEncoder.encode(username, StandardCharsets.UTF_8))
                .append("&serverId=")
                .append(URLEncoder.encode(serverId, StandardCharsets.UTF_8));

        if (address != null) {
            url.append("&ip=").append(URLEncoder.encode(address.getHostAddress(), StandardCharsets.UTF_8));
        }
        if (detail) {
            url.append("&detail=true");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResult(response.statusCode(), response.body());
    }

    /**
     * Parse a 403 error body into an {@link ErrorResponse}.
     *
     * @param body JSON body from a 403 response
     * @return parsed {@link ErrorResponse}, never null
     */
    public ErrorResponse parseError(String body) {
        ErrorResponse err = GSON.fromJson(body, ErrorResponse.class);
        return err != null ? err : new ErrorResponse();
    }

    public record ApiResult(int statusCode, String body) {
        public boolean isSuccess() {
            return statusCode == 200;
        }

        public boolean isForbidden() {
            return statusCode == 403;
        }
    }
}
