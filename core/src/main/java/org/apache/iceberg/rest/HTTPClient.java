/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.impl.EnglishReasonPhraseCatalog;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.relocated.com.google.common.annotations.VisibleForTesting;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HttpClient for usage with the REST catalog.
 */
public class HTTPClient implements RESTClient {

  private static final Logger LOG = LoggerFactory.getLogger(HTTPClient.class);

  private final String uri;
  private final CloseableHttpClient httpClient;
  private final ObjectMapper mapper;
  private final Map<String, String> additionalHeaders;

  private HTTPClient(
      String uri, CloseableHttpClient httpClient, ObjectMapper mapper, Map<String, String> additionalHeaders) {
    this.uri = uri;
    this.httpClient = httpClient != null ? httpClient : HttpClients.createDefault();
    this.mapper = mapper != null ? mapper : new ObjectMapper();
    this.additionalHeaders = additionalHeaders != null ? additionalHeaders : ImmutableMap.of();
  }

  private static String extractResponseBodyAsString(CloseableHttpResponse response) {
    try {
      if (response.getEntity() == null) {
        return null;
      }

      // EntityUtils.toString returns null when HttpEntity.getContent returns null.
      return EntityUtils.toString(response.getEntity(), "UTF-8");
    } catch (IOException | ParseException e) {
      throw new RESTException(e, "Failed to convert HTTP response body to string");
    }
  }

  // Per the spec, the only currently defined / used "success" responses are 200 and 202.
  private static boolean isSuccessful(CloseableHttpResponse response) {
    int code = response.getCode();
    return code ==  HttpStatus.SC_OK || code == HttpStatus.SC_ACCEPTED || code == HttpStatus.SC_NO_CONTENT;
  }

  private static ErrorResponse buildDefaultErrorResponse(CloseableHttpResponse response) {
    String responseReason = response.getReasonPhrase();
    String message =
        responseReason != null && !responseReason.isEmpty() ? responseReason :
            EnglishReasonPhraseCatalog.INSTANCE.getReason(response.getCode(), null /* ignored */);
    String type = "RESTException";
    return ErrorResponse.builder()
        .responseCode(response.getCode())
        .withMessage(message)
        .withType(type)
        .build();
  }

  // Process a failed response through the provided errorHandler, and throw a RESTException if the
  // provided error handler doesn't already throw.
  private static void throwFailure(
      CloseableHttpResponse response, String responseBody, Consumer<ErrorResponse> errorHandler) {
    ErrorResponse errorResponse = null;

    if (responseBody != null) {
      try {
        errorResponse = ErrorResponseParser.fromJson(responseBody);
      } catch (UncheckedIOException | IllegalArgumentException e) {
        // It's possible to receive a non-successful response that isn't a properly defined ErrorResponse
        // without any bugs in the server implementation. So we ignore this exception and build an error
        // response for the user.
        //
        // For example, the connection could time out before every reaching the server, in which case we'll
        // likely get a 5xx with the load balancers default 5xx response.
        LOG.error("Failed to parse an error response. Will create one instead.", e);
      }
    }

    if (errorResponse == null) {
      errorResponse = buildDefaultErrorResponse(response);
    }

    errorHandler.accept(errorResponse);

    // Throw an exception in case the provided error handler does not throw.
    throw new RESTException("Unhandled error: %s", errorResponse);
  }

  /**
   * Method to execute an HTTP request and process the corresponding response.
   *
   * @param method       - HTTP method, such as GET, POST, HEAD, etc.
   * @param path         - URL path to send the request to
   * @param requestBody  - Content to place in the request body
   * @param responseType - Class of the Response type. Needs to have serializer registered with ObjectMapper
   * @param errorHandler - Error handler delegated for HTTP responses which handles server error responses
   * @param <T>          - Class type of the response for deserialization. Must be registered with the ObjectMapper.
   * @return The response entity, parsed and converted to its type T
   */
  private <T> T execute(
      Method method, String path, Object requestBody, Class<T> responseType, Consumer<ErrorResponse> errorHandler) {
    if (path.startsWith("/")) {
      throw new RESTException(
          "Received a malformed path for a REST request: %s. Paths should not start with /", path);
    }

    String fullUri = String.format("%s/%s", uri, path);
    HttpUriRequestBase request = new HttpUriRequestBase(method.name(), URI.create(fullUri));
    addRequestHeaders(request);

    if (requestBody != null) {
      try {
        StringEntity stringEntity = new StringEntity(mapper.writeValueAsString(requestBody));
        request.setEntity(stringEntity);
      } catch (JsonProcessingException e) {
        throw new RESTException(e, "Failed to write request body: %s", requestBody);
      }
    }

    try (CloseableHttpResponse response = httpClient.execute(request)) {

      // Skip parsing the response stream for any successful request not expecting a response body
      if (response.getCode() == HttpStatus.SC_NO_CONTENT || (responseType == null && isSuccessful(response))) {
        return null;
      }

      String responseBody = extractResponseBodyAsString(response);

      if (!isSuccessful(response)) {
        // The provided error handler is expected to throw, but a RESTException is thrown if not.
        throwFailure(response, responseBody, errorHandler);
      }

      if (responseBody == null) {
        throw new RESTException(
            "Invalid (null) response body for request (expected %s): method=%s, path=%s, status=%d",
            responseType.getSimpleName(), method.name(), path, response.getCode());
      }

      try {
        return mapper.readValue(responseBody, responseType);
      } catch (JsonProcessingException e) {
        throw new RESTException(
            "Received a success response code of %d, but failed to parse response body into %s",
            response.getCode(), responseType.getSimpleName());
      }
    } catch (IOException e) {
      throw new RESTException(e, "Error occurred while processing %s request", method);
    }
  }

  @Override
  public void head(String path, Consumer<ErrorResponse> errorHandler) {
    execute(Method.HEAD, path, null, null, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T get(String path, Class<T> responseType,
                                        Consumer<ErrorResponse> errorHandler) {
    return execute(Method.GET, path, null, responseType, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T post(String path, RESTRequest body, Class<T> responseType,
                                         Consumer<ErrorResponse> errorHandler) {
    return execute(Method.POST, path, body, responseType, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T delete(String path, Class<T> responseType,
                                           Consumer<ErrorResponse> errorHandler) {
    return execute(Method.DELETE, path, null, responseType, errorHandler);
  }

  private void addRequestHeaders(HttpUriRequest request) {
    request.setHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
    // Many systems require that content type is set regardless and will fail, even on an empty bodied request.
    request.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
    additionalHeaders.forEach(request::setHeader);
  }

  @Override
  public void close() throws IOException {
    httpClient.close(CloseMode.GRACEFUL);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final Map<String, String> additionalHeaders = Maps.newHashMap();
    private String uri;
    private CloseableHttpClient httpClient;
    private ObjectMapper mapper;

    private Builder() {
    }

    private static String asBearer(String token) {
      return String.format("Bearer %s", token);
    }

    public Builder uri(String baseUri) {
      Preconditions.checkNotNull(baseUri, "Invalid uri for http client: null");
      this.uri = RESTUtil.stripTrailingSlash(baseUri);
      return this;
    }

    @VisibleForTesting
    Builder mapper(ObjectMapper objectMapper) {
      this.mapper = objectMapper;
      return this;
    }

    public Builder withHeader(String key, String value) {
      additionalHeaders.put(key, value);
      return this;
    }

    public Builder withHeaders(Map<String, String> headers) {
      additionalHeaders.putAll(headers);
      return this;
    }

    public Builder withBearerAuth(String token) {
      Preconditions.checkNotNull(token, "Invalid auth token: null");
      additionalHeaders.put(HttpHeaders.AUTHORIZATION, asBearer(token));
      return this;
    }

    public HTTPClient build() {
      return new HTTPClient(uri, httpClient, mapper, additionalHeaders);
    }
  }
}
