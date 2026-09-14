package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.RequestGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class ExecutionPlanTest {

    private static Request req(String path) {
        return Request.newBuilder().path(path).build();
    }

    @Test
    void nullGroupsIsTreatedAsEmpty() {
        ExecutionPlan plan = new ExecutionPlan(null, false,
                false, -1, null);

        assertNotNull(plan.getGroups());
        assertTrue(plan.getGroups().isEmpty());
        assertEquals(0, plan.totalRequestCount());
        assertTrue(plan.allRequests().isEmpty());
    }

    @Test
    void emptyGroupListProducesEmptyPlan() {
        ExecutionPlan plan = new ExecutionPlan(List.of(), false,
                false, -1, null);

        assertTrue(plan.getGroups().isEmpty());
        assertEquals(0, plan.totalRequestCount());
        assertTrue(plan.allRequests().isEmpty());
    }

    @Test
    void singleEmptyGroupCountsAsZeroRequests() {
        RequestGroup emptyGroup = new RequestGroup("empty", List.of());
        ExecutionPlan plan = new ExecutionPlan(List.of(emptyGroup), false,
                false, -1, null);

        assertEquals(1, plan.getGroups().size());
        assertEquals(0, plan.totalRequestCount());
        assertTrue(plan.allRequests().isEmpty());
    }

    @Test
    void groupsArePreservedInOrder() {
        RequestGroup g1 = new RequestGroup("g1", List.of(req("/a")));
        RequestGroup g2 = new RequestGroup("g2", List.of(req("/b")));
        RequestGroup g3 = new RequestGroup("g3", List.of(req("/c")));

        ExecutionPlan plan = new ExecutionPlan(List.of(g1, g2, g3), false,
                false, -1, null);

        assertEquals(3, plan.getGroups().size());
        assertEquals("g1", plan.getGroups().get(0).name());
        assertEquals("g2", plan.getGroups().get(1).name());
        assertEquals("g3", plan.getGroups().get(2).name());
    }

    @Test
    void groupsListIsDefensiveCopy() {
        RequestGroup g1 = new RequestGroup("g1", List.of(req("/a")));
        List<RequestGroup> mutable = new java.util.ArrayList<>(List.of(g1));

        ExecutionPlan plan = new ExecutionPlan(mutable, false, false, -1, null);
        mutable.add(new RequestGroup("intruder", List.of()));

        assertEquals(1, plan.getGroups().size());
    }

    @Test
    void getGroupsReturnsUnmodifiableList() {
        ExecutionPlan plan = new ExecutionPlan(
                List.of(new RequestGroup("g", List.of(req("/x")))), false,
                false, -1, null);

        List<RequestGroup> groups = plan.getGroups();
        assertThrows(UnsupportedOperationException.class,
                () -> groups.add(new RequestGroup(List.of())));
    }

    @Test
    void allRequestsFlattensSingleGroup() {
        Request r1 = req("/1");
        Request r2 = req("/2");
        RequestGroup g = new RequestGroup("g", List.of(r1, r2));

        ExecutionPlan plan = new ExecutionPlan(List.of(g), false,
                false, -1, null);
        List<Request> all = plan.allRequests();

        assertEquals(2, all.size());
        assertEquals(r1, all.get(0));
        assertEquals(r2, all.get(1));
    }

    @Test
    void allRequestsFlattensMultipleGroupsInOrder() {
        Request r1 = req("/g1r1");
        Request r2 = req("/g1r2");
        Request r3 = req("/g2r1");
        Request r4 = req("/g2r2");
        Request r5 = req("/g3r1");

        RequestGroup g1 = new RequestGroup("g1", List.of(r1, r2));
        RequestGroup g2 = new RequestGroup("g2", List.of(r3, r4));
        RequestGroup g3 = new RequestGroup("g3", List.of(r5));

        ExecutionPlan plan = new ExecutionPlan(List.of(g1, g2, g3), false,
                false, -1, null);
        List<Request> all = plan.allRequests();

        assertEquals(5, all.size());
        assertEquals(r1, all.get(0));
        assertEquals(r2, all.get(1));
        assertEquals(r3, all.get(2));
        assertEquals(r4, all.get(3));
        assertEquals(r5, all.get(4));
    }

    @Test
    void allRequestsSkipsEmptyGroups() {
        Request r = req("/only");
        RequestGroup empty = new RequestGroup("empty", List.of());
        RequestGroup withReq = new RequestGroup("real", List.of(r));

        ExecutionPlan plan = new ExecutionPlan(List.of(empty, withReq), false,
                false, -1, null);
        List<Request> all = plan.allRequests();

        assertEquals(1, all.size());
        assertEquals(r, all.getFirst());
    }

    @Test
    void totalRequestCountIsSumOfAllGroupSizes() {
        RequestGroup g1 = new RequestGroup("g1", List.of(req("/a"), req("/b")));
        RequestGroup g2 = new RequestGroup("g2", List.of(req("/c")));
        RequestGroup g3 = new RequestGroup("g3", List.of(req("/d"), req("/e"), req("/f")));

        ExecutionPlan plan = new ExecutionPlan(List.of(g1, g2, g3), false,
                false, -1, null);
        assertEquals(6, plan.totalRequestCount());
    }

    @Test
    void asyncFlagFalseByDefault() {
        ExecutionPlan plan = new ExecutionPlan(List.of(), false,
                false, -1, null);
        assertFalse(plan.isAsync());
    }

    @Test
    void asyncFlagTrueWhenSet() {
        ExecutionPlan plan = new ExecutionPlan(List.of(), true,
                false, -1, null);
        assertTrue(plan.isAsync());
    }

    @Test
    void synchronizeLastBytesFlagFalseByDefault() {
        ExecutionPlan plan = new ExecutionPlan(List.of(), false,
                false, -1, null);
        assertFalse(plan.isSynchronizeLastBytes());
    }

    @Test
    void synchronizeLastBytesFlagTrueWhenSet() {
        ExecutionPlan plan = new ExecutionPlan(List.of(), false,
                true, -1, null);
        assertTrue(plan.isSynchronizeLastBytes());
    }

    @Test
    void threadCountIsPreservedExactly() {
        ExecutionPlan plan5 = new ExecutionPlan(List.of(), false,
                false, 5, null);
        assertEquals(5, plan5.getThreadCount());

        ExecutionPlan planNeg = new ExecutionPlan(List.of(), false,
                false, -1, null);
        assertEquals(-1, planNeg.getThreadCount());

        ExecutionPlan planZero = new ExecutionPlan(List.of(), false,
                false, 0, null);
        assertEquals(0, planZero.getThreadCount());
    }

    @Test
    void clientSupplierNullByDefault() {
        ExecutionPlan plan = new ExecutionPlan(List.of(), false,
                false, -1, null);
        assertNull(plan.getClientSupplier());
    }

    @Test
    void clientSupplierIsPreserved() {
        Supplier<HttpClient> s = () -> { throw new UnsupportedOperationException(); };
        ExecutionPlan plan = new ExecutionPlan(List.of(), false,
                false, -1, s);
        assertSame(s, plan.getClientSupplier());
    }
}
