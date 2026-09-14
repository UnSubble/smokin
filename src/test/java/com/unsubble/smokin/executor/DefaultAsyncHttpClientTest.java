package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultAsyncHttpClientTest {

    private static final int TIMEOUT_SECONDS = 5;

    private HttpClient successClient(Response response) {
        return fakeClient(req -> response);
    }

    private HttpClient failingClient(IOException error) {
        return fakeClient(req -> { throw error; });
    }

    @FunctionalInterface
    private interface ClientAction {
        Response apply(Request r) throws IOException;
    }

    private HttpClient fakeClient(ClientAction action) {
        return new HttpClient(null, null, null, null) {
            @Override
            public Response send(Request request) throws IOException {
                return action.apply(request);
            }
        };
    }

    private Response stubResponse(int status) {
        return Response.newBuilder().statusCode(status).build();
    }

    private Request anyRequest() {
        return Request.newBuilder().build();
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void sendAsyncCompletesWithResponseOnSuccess() throws Exception {
        Response expected = stubResponse(200);
        DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(
                successClient(expected), Runnable::run); // inline executor for determinism

        CompletableFuture<Response> future = client.sendAsync(anyRequest());
        Response actual = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertSame(expected, actual);
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void sendAsyncPassesRequestToUnderlyingClient() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        HttpClient capturer = fakeClient(req -> {
            captured.set(req);
            return stubResponse(200);
        });

        Request sent = Request.newBuilder().path("/specific-path").build();
        DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(capturer, Runnable::run);
        client.sendAsync(sent).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertSame(sent, captured.get(),
                "The exact same Request object must be forwarded to HttpClient.send()");
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void sendAsyncCompletesExceptionallyWhenClientThrowsIOException() {
        IOException root = new IOException("connection refused");
        DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(
                failingClient(root), Runnable::run);

        CompletableFuture<Response> future = client.sendAsync(anyRequest());

        assertTrue(future.isCompletedExceptionally());

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        Throwable cause = ex.getCause();
        assertNotNull(cause, "Cause must not be null");

        Throwable rootCause = cause;
        while (rootCause != null && rootCause != root) {
            rootCause = rootCause.getCause();
        }
        assertSame(root, rootCause,
                "The original IOException must be findable in the cause chain of ExecutionException");
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void sendAsyncDoesNotSwallowIOException() {
        IOException expected = new IOException("network error");
        DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(
                failingClient(expected), Runnable::run);

        CompletableFuture<Response> future = client.sendAsync(anyRequest());

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        Throwable rootCause = ex.getCause();
        while (rootCause != null && !(rootCause instanceof IOException)) {
            rootCause = rootCause.getCause();
        }
        assertSame(expected, rootCause,
                "The original IOException must not be swallowed; it must appear in the cause chain");
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void taskRunsOnProvidedExecutor() throws Exception {
        AtomicBoolean ranOnExecutor = new AtomicBoolean(false);
        Executor trackingExecutor = task -> {
            ranOnExecutor.set(true);
            task.run();
        };

        DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(
                successClient(stubResponse(200)), trackingExecutor);
        client.sendAsync(anyRequest()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(ranOnExecutor.get(),
                "Async task must run on the provided executor, not the calling thread");
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void doesNotShutDownProvidedExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(
                    successClient(stubResponse(200)), executor);

            client.sendAsync(anyRequest()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertFalse(executor.isShutdown(),
                    "DefaultAsyncHttpClient must NOT shut down the executor it was given");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @Timeout(TIMEOUT_SECONDS)
    void multipleConcurrentRequestsEachCompleteIndependently() throws Exception {
        int count = 5;
        Response[] responses = new Response[count];
        for (int i = 0; i < count; i++) {
            responses[i] = stubResponse(200 + i);
        }

        AtomicInteger callCount = new AtomicInteger(0);
        HttpClient multi = fakeClient(req -> responses[callCount.getAndIncrement() % count]);

        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(multi, executor);

            @SuppressWarnings("unchecked")
            CompletableFuture<Response>[] futures = new CompletableFuture[count];
            for (int i = 0; i < count; i++) {
                futures[i] = client.sendAsync(anyRequest());
            }

            CompletableFuture.allOf(futures).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            for (CompletableFuture<Response> f : futures) {
                assertTrue(f.isDone());
                assertFalse(f.isCompletedExceptionally());
            }
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void implementsAsyncHttpClientInterface() {
        DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(
                successClient(stubResponse(200)), Runnable::run);
        assertInstanceOf(AsyncHttpClient.class, client,
                "DefaultAsyncHttpClient must implement AsyncHttpClient");
    }

    @Test
    void doesNotRequireLastByteSpecificMethods() throws Exception {
        AtomicBoolean sendCalled = new AtomicBoolean(false);
        AtomicBoolean writeCalled = new AtomicBoolean(false);

        HttpClient noWriteClient = new HttpClient(null, null, null, null) {
            @Override
            public Response send(Request request) {
                sendCalled.set(true);
                return stubResponse(200);
            }

            @Override
            public void write(Request request) {
                writeCalled.set(true);
                fail("DefaultAsyncHttpClient must not call write(Request) directly");
            }

            @Override
            public void write(byte[] data) {
                writeCalled.set(true);
                fail("DefaultAsyncHttpClient must not call write(byte[]) directly");
            }
        };

        DefaultAsyncHttpClient client = new DefaultAsyncHttpClient(noWriteClient, Runnable::run);
        client.sendAsync(anyRequest()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(sendCalled.get(), "HttpClient.send() must be called");
        assertFalse(writeCalled.get(), "HttpClient.write() must NOT be called by DefaultAsyncHttpClient");
    }
}
