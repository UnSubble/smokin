package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

public class DefaultAsyncHttpClient implements AsyncHttpClient {

    private final HttpClient client;
    private final Executor executor;

    public DefaultAsyncHttpClient(HttpClient client, Executor executor) {
        this.client = client;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Response> sendAsync(Request request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.send(request);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
}