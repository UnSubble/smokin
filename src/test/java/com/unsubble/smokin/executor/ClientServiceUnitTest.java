package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.RequestGroup;
import com.unsubble.smokin.model.Response;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10) // global safety net
public class ClientServiceUnitTest {

    private static Response resp(int status) {
        return Response.newBuilder().statusCode(status).build();
    }

    private static Request req(String path) {
        return Request.newBuilder().path(path).build();
    }

    private static Request reqWithBody(String path, byte[] body) {
        return Request.newBuilder().path(path).body(body).build();
    }

    private static HttpClient stubClient(Response response) {
        return new HttpClient(null, null, null, null) {
            @Override
            public Response send(Request request) {
                return response;
            }

            @Override
            public void write(Request request) {
            }

            @Override
            public void write(byte[] data) {
            }

            @Override
            public Response read(String method) {
                return response;
            }
        };
    }

    private static HttpClient failingClient(IOException ex) {
        return new HttpClient(null, null, null, null) {

            @Override
            public Response send(Request request) throws IOException {
                throw ex;
            }

            @Override
            public void write(Request request) throws IOException {
                throw ex;
            }

            @Override
            public void write(byte[] data) throws IOException {
                throw ex;
            }

            @Override
            public Response read(String method) throws IOException {
                throw ex;
            }
        };
    }

    private static Supplier<HttpClient> always(HttpClient c) {
        return () -> c;
    }

    @Test
    void defaultThreadCountIsTen() throws IOException {
        ClientService svc = new ClientService();
        assertEquals(10, svc.getDefaultThreadCount());
        assertEquals(ClientService.DEFAULT_THREAD_COUNT, svc.getDefaultThreadCount());
        svc.close();
    }

    @Test
    void customDefaultThreadCountIsRespected() throws IOException {
        ClientService svc = new ClientService((Supplier<HttpClient>) null, 5);
        assertEquals(5, svc.getDefaultThreadCount());
        svc.close();
    }

    @Test
    void invalidDefaultThreadCountFallsBackToDefault() throws IOException {
        ClientService svc0 = new ClientService((Supplier<HttpClient>) null, 0);
        ClientService svcNeg = new ClientService((Supplier<HttpClient>) null, -3);

        assertEquals(ClientService.DEFAULT_THREAD_COUNT, svc0.getDefaultThreadCount());
        assertEquals(ClientService.DEFAULT_THREAD_COUNT, svcNeg.getDefaultThreadCount());

        svcNeg.close();
        svc0.close();
    }

    @Test
    void executorIsNullBeforeFirstAsyncExecution() throws IOException {
        ClientService svc = new ClientService();
        assertNull(svc.getExecutorService());
        svc.close();
    }

    @Test
    void executorIsCreatedLazilyOnFirstAsyncRequest() throws IOException {
        ClientService svc = new ClientService(always(stubClient(resp(200))));
        assertNull(svc.getExecutorService(), "Executor must be null before any execution");

        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                true, false, 2, null);
        svc.execute(plan);

        assertNotNull(svc.getExecutorService(), "Executor must be created after async execution");
        svc.close();
    }

    @Test
    void syncExecutionDoesNotCreateExecutor() throws IOException {
        ClientService svc = new ClientService(always(stubClient(resp(200))));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                false, false, -1, null);
        svc.execute(plan);
        assertNull(svc.getExecutorService(), "Sync execution must not create an executor");
        svc.close();
    }

    @Test
    void sameExecutorIsReusedAcrossMultipleAsyncExecutions() throws IOException {
        ClientService svc = new ClientService(always(stubClient(resp(200))));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                true, false, 2, null);

        svc.execute(plan);
        ExecutorService first = svc.getExecutorService();
        svc.execute(plan);
        ExecutorService second = svc.getExecutorService();

        assertSame(first, second, "Same executor must be reused across calls");
        svc.close();
    }

    @Test
    void shutdownMarksServiceAsShutdown() throws Exception {
        ClientService svc = new ClientService(always(stubClient(resp(200))));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                true, false, 1, null);
        svc.execute(plan);

        assertFalse(svc.isShutdown());
        svc.shutdown();
        assertTrue(svc.isShutdown());
        svc.close();
    }

    @Test
    void shutdownBeforeAnyAsyncExecutionIsNoOp() throws IOException {
        ClientService svc = new ClientService();
        assertDoesNotThrow(svc::shutdown);
        assertFalse(svc.isShutdown()); // executor was never created
        svc.close();
    }

    @Test
    void isShutdownFalseWhenNoExecutorExists() throws IOException {
        ClientService svc = new ClientService();
        assertFalse(svc.isShutdown());
        svc.close();
    }

    @Test
    void awaitTerminationReturnsTrueWhenNoExecutorExists() throws Exception {
        ClientService svc = new ClientService();
        assertTrue(svc.awaitTermination(0, TimeUnit.SECONDS));
        svc.close();
    }

    @Test
    void closeCallsShutdown() throws IOException {
        ClientService svc = new ClientService(always(stubClient(resp(200))));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                true, false, 1, null);
        svc.execute(plan);

        assertFalse(svc.isShutdown());
        svc.close();
        assertTrue(svc.isShutdown());
    }

    @Test
    void emptyRequestListReturnsEmptyResult() throws IOException {
        ClientService svc = new ClientService();
        ExecutionPlan plan = new ExecutionPlan(List.of(), false, false, -1, null);
        ExecutionResult result = svc.execute(plan);

        assertNotNull(result);
        assertEquals(0, result.size());
        svc.close();
    }

    @Test
    void emptyGroupInsidePlanStillProducesEmptyResult() throws IOException {
        ClientService svc = new ClientService();
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup("empty", List.of())),
                false, false, -1, null);
        ExecutionResult result = svc.execute(plan);
        assertEquals(0, result.size());
        svc.close();
    }

    @Test
    void executeWithNoClientThrowsIllegalState() throws IOException {
        ClientService svc = new ClientService(); // no default client
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                false, false, -1, null);
        assertThrows(IllegalStateException.class, () -> svc.execute(plan));
        svc.close();
    }

    @Test
    void planClientSupplierTakesPrecedenceOverDefault() throws Exception {
        HttpClient defaultClient = failingClient(new IOException("should not be called"));
        HttpClient planClient = stubClient(resp(201));

        ClientService svc = new ClientService(always(defaultClient));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                false, false, -1, always(planClient));

        ExecutionResult result = svc.execute(plan);
        assertEquals(201, result.future(0).get().statusCode());
        svc.close();
    }

    @Test
    void syncExecutionCallsSendOnCallingThread() throws Exception {
        AtomicReference<String> senderThreadName = new AtomicReference<>();
        String callerName = Thread.currentThread().getName();

        HttpClient tracker = new HttpClient(null, null, null, null) {
            @Override
            public Response send(Request request) {
                senderThreadName.set(Thread.currentThread().getName());
                return resp(200);
            }
        };

        ClientService svc = new ClientService(always(tracker));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                false, false, -1, null);
        svc.execute(plan);

        assertEquals(callerName, senderThreadName.get(),
                "Sync execution must run on the calling thread");

        svc.close();
    }

    @Test
    void syncExecutionReturnsCompletedFuturesInOrder() throws Exception {
        AtomicInteger callIndex = new AtomicInteger(0);
        Response[] resps = {resp(200), resp(201), resp(202)};

        HttpClient ordered = new HttpClient(null, null, null, null) {
            @Override
            public Response send(Request request) {
                return resps[callIndex.getAndIncrement()];
            }
        };

        ClientService svc = new ClientService(always(ordered));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/a"), req("/b"), req("/c")))),
                false, false, -1, null);

        ExecutionResult result = svc.execute(plan);
        assertEquals(3, result.size());

        for (int i = 0; i < 3; i++) {
            assertTrue(result.future(i).isDone());
            assertEquals(resps[i].statusCode(), result.future(i).get().statusCode());
        }

        svc.close();
    }

    @Test
    void syncExecutionFailureIsWrappedInFailedFuture() throws Exception {
        IOException ex = new IOException("network error");
        ClientService svc = new ClientService(always(failingClient(ex)));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                false, false, -1, null);

        ExecutionResult result = svc.execute(plan);
        assertEquals(1, result.size());
        assertTrue(result.future(0).isCompletedExceptionally());

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> result.future(0).get());
        assertSame(ex, thrown.getCause());

        svc.close();
    }

    @Test
    @Timeout(10)
    void asyncExecutionCompletesAllFutures() throws Exception {
        Response expected = resp(200);
        ClientService svc = new ClientService(always(stubClient(expected)));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/a"), req("/b"), req("/c")))),
                true, false, 3, null);

        ExecutionResult result = svc.execute(plan);
        List<Response> responses = result.all().get(5, TimeUnit.SECONDS);

        assertEquals(3, responses.size());
        responses.forEach(r -> assertEquals(200, r.statusCode()));
        svc.close();
    }

    @Test
    @Timeout(10)
    void asyncExecutionFailureIsPerFutureNotGlobal() throws Exception {
        IOException ex = new IOException("fail");
        ClientService svc = new ClientService(always(failingClient(ex)));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x"), req("/y")))),
                true, false, 2, null);

        ExecutionResult result = svc.execute(plan);

        // Both futures complete exceptionally, but the execute() call itself should not throw
        for (int i = 0; i < 2; i++) {
            int idx = i;
            ExecutionException thrown = assertThrows(ExecutionException.class,
                    () -> result.future(idx).get(5, TimeUnit.SECONDS));
            Throwable cause = thrown.getCause();
            assertTrue(cause instanceof CompletionException || cause instanceof IOException);
        }
        svc.close();
    }

    @Test
    @Timeout(10)
    void asyncFuturesReturnedInInputOrder() throws Exception {
        List<Request> requests = List.of(req("/1"), req("/2"), req("/3"));
        AtomicInteger idx = new AtomicInteger(0);
        Response[] resps = {resp(200), resp(201), resp(202)};

        ClientService svc = getSvc(idx, resps);
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(requests)),
                true, false, 3, null);

        ExecutionResult result = svc.execute(plan);

        for (int i = 0; i < 3; i++) {
            Response r = result.future(i).get(5, TimeUnit.SECONDS);
            assertEquals(resps[i].statusCode(), r.statusCode(),
                    "Future at index " + i + " must correspond to input request at index " + i);
        }
        svc.close();
    }

    private static @NonNull ClientService getSvc(AtomicInteger idx, Response[] resps) {
        HttpClient ordered = new HttpClient(null, null, null, null) {
            @Override
            public Response send(Request request) throws IOException {
                int i = idx.getAndIncrement();
                try {
                    Thread.sleep((resps.length - i) * 10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return resps[i];
            }
        };

        return new ClientService(always(ordered));
    }

    @Test
    @Timeout(10)
    void supplierIsCalledOncePerRequest() throws Exception {
        AtomicInteger supplierCallCount = new AtomicInteger(0);
        Supplier<HttpClient> countingSupplier = () -> {
            supplierCallCount.incrementAndGet();
            return stubClient(resp(200));
        };

        ClientService svc = new ClientService(countingSupplier);
        int reqCount = 4;
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < reqCount; i++) {
            requests.add(req("/" + i));
        }

        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(requests)),
                false, false, -1, null);
        svc.execute(plan);

        assertEquals(reqCount, supplierCallCount.get(),
                "Supplier must be called exactly once per request");

        svc.close();
    }

    @Test
    void lastByteSyncWithMultipleRequestsAndNoAsyncThrows() throws IOException {
        ClientService svc = new ClientService(always(stubClient(resp(200))));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/a"), req("/b")))),
                false, true, -1, null); // sync=false, lastByte=true, 2 requests

        assertThrows(IllegalArgumentException.class, () -> svc.execute(plan));

        svc.close();
    }

    @Test
    @Timeout(10)
    void lastByteSyncWithSingleRequestWorksWithoutAsync() throws Exception {
        HttpClient tracked = new HttpClient(null, null, null, null) {
            @Override
            public void write(Request request) {}
            @Override
            public void write(byte[] data) {}
            @Override
            public Response read(String method) { return resp(200); }
        };

        ClientService svc = new ClientService(always(tracked));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(reqWithBody("/x", new byte[]{1, 2})))),
                false, true, -1, null);

        ExecutionResult result = svc.execute(plan);
        assertEquals(1, result.size());
        assertEquals(200, result.future(0).get().statusCode());

        svc.close();
    }

    @Test
    @Timeout(10)
    void sameClientInstanceUsedForFirstAndLastPartOfSameRequest() throws Exception {
        AtomicReference<HttpClient> writeFirstRef = new AtomicReference<>();
        AtomicReference<HttpClient> writeLastRef = new AtomicReference<>();
        ClientService svc = getSvc(writeFirstRef, writeLastRef);
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(reqWithBody("/x", new byte[]{1, 2, 3})))),
                true, true, 1, null);

        ExecutionResult result = svc.execute(plan);
        result.future(0).get(5, TimeUnit.SECONDS);

        assertNotNull(writeFirstRef.get(), "First part write must have been called");
        assertNotNull(writeLastRef.get(), "Last byte write must have been called");
        assertSame(writeFirstRef.get(), writeLastRef.get(),
                "First part and last byte MUST use the SAME HttpClient instance");
        svc.shutdown();
    }

    private @NonNull ClientService getSvc(AtomicReference<HttpClient> writeFirstRef, AtomicReference<HttpClient> writeLastRef) {
        CountDownLatch done = new CountDownLatch(1);

        Supplier<HttpClient> trackingSupplier = () -> new HttpClient(null, null, null, null) {
            @Override
            public void write(Request request) {
                writeFirstRef.set(this);
            }
            @Override
            public void write(byte[] data) {
                writeLastRef.set(this);
            }
            @Override
            public Response read(String method) {
                done.countDown();
                return resp(200);
            }
        };

        return new ClientService(trackingSupplier);
    }

    @Test
    void executeWithNullPlanThrowsNPE() throws IOException {
        ClientService svc = new ClientService();
        assertThrows(NullPointerException.class, () -> svc.execute(null));
        svc.close();
    }

    @Test
    void createControllerReturnsClientCtlWithSameService() {
        ClientService svc = new ClientService();
        ClientCtl ctl = svc.createController();
        assertNotNull(ctl);
        assertSame(svc, ctl.clientService());
    }

    @Test
    @Timeout(10)
    void shutdownExecutorIsRecreatedOnNextAsyncExecution() throws Exception {
        ClientService svc = new ClientService(always(stubClient(resp(200))));
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup(List.of(req("/x")))),
                true, false, 1, null);

        svc.execute(plan).future(0).get(5, TimeUnit.SECONDS);
        ExecutorService first = svc.getExecutorService();
        svc.shutdown();
        assertTrue(svc.isShutdown());

        svc.execute(plan).future(0).get(5, TimeUnit.SECONDS);
        ExecutorService second = svc.getExecutorService();

        assertNotNull(second);
        assertFalse(second.isShutdown());

        assertNotSame(first, second,
                "A new executor must be created after the old one was shut down");
        svc.close();
    }
}
