package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class ExecutionResultTest {

    private static Response resp(int status) {
        return Response.newBuilder().statusCode(status).build();
    }

    @Test
    void nullFuturesListProducesEmptyResult() {
        ExecutionResult result = new ExecutionResult(null);
        assertEquals(0, result.size());
        assertTrue(result.futures().isEmpty());
    }

    @Test
    void emptyFuturesListProducesEmptyResult() {
        ExecutionResult result = new ExecutionResult(List.of());
        assertEquals(0, result.size());
        assertTrue(result.futures().isEmpty());
    }

    @Test
    void futuresListIsImmutable() {
        List<CompletableFuture<Response>> mutable = new ArrayList<>();
        mutable.add(CompletableFuture.completedFuture(resp(200)));
        ExecutionResult result = new ExecutionResult(mutable);

        List<CompletableFuture<Response>> futures = result.futures();
        assertThrows(UnsupportedOperationException.class,
                () -> futures.add(new CompletableFuture<>()));
    }

    @Test
    void externalMutationOfSourceListDoesNotAffectResult() {
        List<CompletableFuture<Response>> source = new ArrayList<>();
        CompletableFuture<Response> f1 = CompletableFuture.completedFuture(resp(200));
        source.add(f1);

        ExecutionResult result = new ExecutionResult(source);

        source.add(new CompletableFuture<>());

        assertEquals(1, result.size());
    }

    @Test
    void sizeReflectsFutureCount() {
        List<CompletableFuture<Response>> futures = List.of(
                CompletableFuture.completedFuture(resp(200)),
                CompletableFuture.completedFuture(resp(201)),
                CompletableFuture.completedFuture(resp(202))
        );
        ExecutionResult result = new ExecutionResult(futures);
        assertEquals(3, result.size());
    }

    @Test
    void futureByIndexReturnsCorrectFuture() {
        CompletableFuture<Response> f0 = CompletableFuture.completedFuture(resp(200));
        CompletableFuture<Response> f1 = CompletableFuture.completedFuture(resp(201));
        CompletableFuture<Response> f2 = CompletableFuture.completedFuture(resp(404));

        ExecutionResult result = new ExecutionResult(List.of(f0, f1, f2));

        assertSame(f0, result.future(0));
        assertSame(f1, result.future(1));
        assertSame(f2, result.future(2));
    }

    @Test
    void futureWithNegativeIndexThrows() {
        ExecutionResult result = new ExecutionResult(
                List.of(CompletableFuture.completedFuture(resp(200))));
        assertThrows(IndexOutOfBoundsException.class, () -> result.future(-1));
    }

    @Test
    void futureWithIndexEqualToSizeThrows() {
        ExecutionResult result = new ExecutionResult(
                List.of(CompletableFuture.completedFuture(resp(200))));
        assertThrows(IndexOutOfBoundsException.class, () -> result.future(1));
    }

    @Test
    void futureWithIndexGreaterThanSizeThrows() {
        ExecutionResult result = new ExecutionResult(List.of());
        assertThrows(IndexOutOfBoundsException.class, () -> result.future(0));
    }

    @Test
    void completedFutureValueIsAccessible() throws Exception {
        Response expected = resp(200);
        ExecutionResult result = new ExecutionResult(
                List.of(CompletableFuture.completedFuture(expected)));

        Response actual = result.future(0).get();
        assertSame(expected, actual);
    }

    @Test
    void allOnEmptyResultCompletesWithEmptyList() throws Exception {
        ExecutionResult result = new ExecutionResult(List.of());
        List<Response> responses = result.all().get(1, TimeUnit.SECONDS);
        assertNotNull(responses);
        assertTrue(responses.isEmpty());
    }

    @Test
    void allResolvesInInsertionOrder() throws Exception {
        Response r0 = resp(200);
        Response r1 = resp(201);
        Response r2 = resp(404);

        ExecutionResult result = new ExecutionResult(List.of(
                CompletableFuture.completedFuture(r0),
                CompletableFuture.completedFuture(r1),
                CompletableFuture.completedFuture(r2)
        ));

        List<Response> responses = result.all().get(1, TimeUnit.SECONDS);
        assertEquals(3, responses.size());
        assertSame(r0, responses.get(0));
        assertSame(r1, responses.get(1));
        assertSame(r2, responses.get(2));
    }

    @Test
    void joinAllBlocksAndReturnsResponses() {
        Response r0 = resp(200);
        Response r1 = resp(201);
        ExecutionResult result = new ExecutionResult(List.of(
                CompletableFuture.completedFuture(r0),
                CompletableFuture.completedFuture(r1)
        ));

        List<Response> responses = result.joinAll();
        assertEquals(2, responses.size());
        assertSame(r0, responses.get(0));
        assertSame(r1, responses.get(1));
    }

    @Test
    void exceptionalFutureIsAccessibleViaFutureIndex() {
        RuntimeException ex = new RuntimeException("boom");
        CompletableFuture<Response> failed = CompletableFuture.failedFuture(ex);
        ExecutionResult result = new ExecutionResult(List.of(failed));

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> result.future(0).get());
        assertSame(ex, thrown.getCause());
    }

    @Test
    void allThrowsIfAnyFutureIsExceptional() {
        CompletableFuture<Response> ok = CompletableFuture.completedFuture(resp(200));
        CompletableFuture<Response> bad = CompletableFuture.failedFuture(new RuntimeException("fail"));

        ExecutionResult result = new ExecutionResult(List.of(ok, bad));

        assertThrows(Exception.class, () -> result.all().get(1, TimeUnit.SECONDS));
    }

    @Test
    void joinAllThrowsIfAnyFutureIsExceptional() {
        CompletableFuture<Response> bad = CompletableFuture.failedFuture(new RuntimeException("fail"));
        ExecutionResult result = new ExecutionResult(List.of(bad));

        assertThrows(CompletionException.class, result::joinAll);
    }

    @Test
    void pendingFutureBlocksAllUntilCompleted() throws Exception {
        CompletableFuture<Response> pending = new CompletableFuture<>();
        CompletableFuture<Response> ready = CompletableFuture.completedFuture(resp(200));

        ExecutionResult result = new ExecutionResult(List.of(ready, pending));

        CompletableFuture<List<Response>> all = result.all();
        assertFalse(all.isDone());

        Response expected = resp(201);
        pending.complete(expected);

        List<Response> responses = all.get(1, TimeUnit.SECONDS);
        assertEquals(2, responses.size());
        assertSame(expected, responses.get(1));
    }

    @Test
    void timeoutPropagatesAsExecutionException() {
        CompletableFuture<Response> neverCompletes = new CompletableFuture<>();
        ExecutionResult result = new ExecutionResult(List.of(neverCompletes));

        assertThrows(TimeoutException.class,
                () -> result.future(0).get(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void futuresCompletedOutOfOrderStillReturnedInInsertionOrder() throws Exception {
        CompletableFuture<Response> f0 = new CompletableFuture<>();
        CompletableFuture<Response> f1 = new CompletableFuture<>();
        CompletableFuture<Response> f2 = new CompletableFuture<>();

        ExecutionResult result = new ExecutionResult(List.of(f0, f1, f2));

        Response r2 = resp(202);
        Response r1 = resp(201);
        Response r0 = resp(200);

        f2.complete(r2);
        f1.complete(r1);
        f0.complete(r0);

        List<Response> responses = result.all().get(1, TimeUnit.SECONDS);
        assertEquals(3, responses.size());
        assertSame(r0, responses.get(0));
        assertSame(r1, responses.get(1));
        assertSame(r2, responses.get(2));
    }
}
