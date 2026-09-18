package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.RequestGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class ClientCtlExtendedTest {

    @Test
    void constructorWithNullServiceThrows() {
        assertThrows(NullPointerException.class, () -> new ClientCtl(null));
    }

    @Test
    void clientServiceAccessor() {
        ClientService svc = new ClientService();
        ClientCtl ctl = new ClientCtl(svc);
        assertSame(svc, ctl.clientService());
    }

    @Test
    void emptyControllerProducesEmptyPlan() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ExecutionPlan plan = ctl.buildPlan();

        assertNotNull(plan);
        assertTrue(plan.getGroups().isEmpty());
        assertEquals(0, plan.totalRequestCount());
        assertTrue(plan.allRequests().isEmpty());
        assertFalse(plan.isAsync());
        assertFalse(plan.isSynchronizeLastBytes());
        assertEquals(-1, plan.getThreadCount());
        assertNull(plan.getClientSupplier());
    }

    @Test
    void addGroupWithRequestListCreatesAnonymousGroup() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        Request r1 = Request.newBuilder().path("/a").build();
        Request r2 = Request.newBuilder().path("/b").build();

        ctl.addGroup(List.of(r1, r2));

        assertEquals(1, ctl.groups().size());
        assertEquals(2, ctl.groups().getFirst().requests().size());
    }

    @Test
    void addGroupWithNameAndRequestListUsesProvidedName() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        Request r = Request.newBuilder().path("/x").build();

        ctl.addGroup("my-group", List.of(r));

        assertEquals(1, ctl.groups().size());
        assertEquals("my-group", ctl.groups().getFirst().name());
    }

    @Test
    void addGroupWithRequestGroupAddsItDirectly() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        Request r = Request.newBuilder().path("/g").build();
        RequestGroup group = new RequestGroup("explicit", List.of(r));

        ctl.addGroup(group);

        assertEquals(1, ctl.groups().size());
        assertSame(group.requests().getFirst(), ctl.groups().getFirst().requests().getFirst());
    }

    @Test
    void addGroupWithNullRequestGroupThrows() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertThrows(NullPointerException.class, () -> ctl.addGroup((RequestGroup) null));
    }

    @Test
    void addGroupWithNullRequestListIsHandledGracefully() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertDoesNotThrow(() -> ctl.addGroup((List<Request>) null));
    }

    @Test
    void multipleGroupsAreAllPreserved() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        Request r1 = Request.newBuilder().path("/1").build();
        Request r2 = Request.newBuilder().path("/2").build();
        Request r3 = Request.newBuilder().path("/3").build();

        ctl.addGroup("g1", List.of(r1))
           .addGroup("g2", List.of(r2))
           .addGroup("g3", List.of(r3));

        assertEquals(3, ctl.groups().size());
        assertEquals("g1", ctl.groups().get(0).name());
        assertEquals("g2", ctl.groups().get(1).name());
        assertEquals("g3", ctl.groups().get(2).name());
    }

    @Test
    void groupsReturnedListIsImmutable() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.addGroup(List.of(Request.newBuilder().build()));

        List<RequestGroup> groups = ctl.groups();
        assertThrows(UnsupportedOperationException.class, () -> groups.add(new RequestGroup(List.of())));
    }

    @Test
    void externalMutationOfGroupsListDoesNotAffectController() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.addGroup(List.of(Request.newBuilder().build()));

        List<RequestGroup> snapshot = ctl.groups();
        ctl.addGroup("extra", List.of(Request.newBuilder().build()));

        assertEquals(1, snapshot.size(), "Old snapshot should not see new group");
        assertEquals(2, ctl.groups().size(), "Controller should see new group");
    }

    @Test
    void addGroupReturnsSameInstance() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertSame(ctl, ctl.addGroup(List.of()));
    }

    @Test
    void addGroupWithNameReturnsSameInstance() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertSame(ctl, ctl.addGroup("x", List.of()));
    }

    @Test
    void addGroupWithRequestGroupReturnsSameInstance() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertSame(ctl, ctl.addGroup(new RequestGroup(List.of())));
    }

    @Test
    void activateAsyncReturnsSameInstance() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertSame(ctl, ctl.activateAsync());
    }

    @Test
    void synchronizeLastBytesReturnsSameInstance() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertSame(ctl, ctl.synchronizeLastBytes());
    }

    @Test
    void threadCountReturnsSameInstance() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertSame(ctl, ctl.threadCount(4));
    }

    @Test
    void clientSupplierReturnsSameInstance() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        Supplier<HttpClient> s = () -> { throw new UnsupportedOperationException(); };
        assertSame(ctl, ctl.clientSupplier(s));
    }

    @Test
    void asyncDefaultIsFalse() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertFalse(ctl.isAsync());
    }

    @Test
    void activateAsyncSetsFlag() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.activateAsync();
        assertTrue(ctl.isAsync());
    }

    @Test
    void activateAsyncIsIdempotent() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.activateAsync().activateAsync();
        assertTrue(ctl.isAsync());
    }

    @Test
    void synchronizeLastBytesDefaultIsFalse() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertFalse(ctl.isSynchronizeLastBytes());
    }

    @Test
    void synchronizeLastBytesSetsFlag() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.synchronizeLastBytes();
        assertTrue(ctl.isSynchronizeLastBytes());
    }

    @Test
    void threadCountDefaultIsMinusOne() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertEquals(-1, ctl.threadCount());
    }

    @Test
    void threadCountZeroThrows() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertThrows(IllegalArgumentException.class, () -> ctl.threadCount(0));
    }

    @Test
    void threadCountNegativeThrows() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertThrows(IllegalArgumentException.class, () -> ctl.threadCount(-1));
        assertThrows(IllegalArgumentException.class, () -> ctl.threadCount(Integer.MIN_VALUE));
    }

    @Test
    void threadCountPositiveIsAccepted() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.threadCount(1);
        assertEquals(1, ctl.threadCount());
        ctl.threadCount(100);
        assertEquals(100, ctl.threadCount());
    }

    @Test
    void clientSupplierNullByDefault() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        assertNull(ctl.buildPlan().getClientSupplier());
    }

    @Test
    void clientSupplierIsCarriedToPlan() {
        Supplier<HttpClient> s = () -> { throw new UnsupportedOperationException(); };
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.clientSupplier(s);
        assertSame(s, ctl.buildPlan().getClientSupplier());
    }

    @Test
    void buildPlanPropagatesAllState() {
        Supplier<HttpClient> supplier = () -> { throw new UnsupportedOperationException(); };
        ClientCtl ctl = new ClientCtl(new ClientService());
        Request r1 = Request.newBuilder().path("/1").build();
        Request r2 = Request.newBuilder().path("/2").build();

        ctl.addGroup("g1", List.of(r1))
           .addGroup("g2", List.of(r2))
           .activateAsync()
           .synchronizeLastBytes()
           .threadCount(7)
           .clientSupplier(supplier);

        ExecutionPlan plan = ctl.buildPlan();

        assertEquals(2, plan.getGroups().size());
        assertEquals(2, plan.totalRequestCount());
        assertTrue(plan.isAsync());
        assertTrue(plan.isSynchronizeLastBytes());
        assertEquals(7, plan.getThreadCount());
        assertSame(supplier, plan.getClientSupplier());
    }

    @Test
    void buildPlanDoesNotMutateController() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.addGroup(List.of(Request.newBuilder().build()));

        ExecutionPlan plan1 = ctl.buildPlan();

        ctl.addGroup(List.of(Request.newBuilder().build()));
        ExecutionPlan plan2 = ctl.buildPlan();

        assertEquals(1, plan1.getGroups().size());
        assertEquals(2, plan2.getGroups().size());
    }

    @Test
    void executeWithNoRequestsReturnsEmptyResult() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ExecutionResult result = ctl.execute();
        assertNotNull(result);
        assertEquals(0, result.size());
        assertTrue(result.futures().isEmpty());
    }

    @Test
    void executeRequiresClientWhenRequestsPresent() {
        ClientCtl ctl = new ClientCtl(new ClientService());
        ctl.addGroup(List.of(Request.newBuilder().build()));

        assertThrows(IllegalStateException.class, ctl::execute);
    }
}
