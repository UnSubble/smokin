package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;

import java.util.concurrent.CompletableFuture;

public interface AsyncHttpClient {

    CompletableFuture<Response> sendAsync(Request request);
}
