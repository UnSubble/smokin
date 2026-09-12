package com.unsubble.smokin.executor;

import com.unsubble.smokin.model.Request;
import com.unsubble.smokin.model.RequestGroup;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

public class ClientCtlTest {

    @Test
    public void testCreateRequestGroupAndAddMultipleGroups() {
        ClientService service = new ClientService();
        ClientCtl ctl = new ClientCtl(service);

        Request req1 = Request.newBuilder().path("/r1").build();
        Request req2 = Request.newBuilder().path("/r2").build();
        Request req3 = Request.newBuilder().path("/r3").build();

        ctl.addGroup(List.of(req1, req2))
           .addGroup("custom-group", List.of(req3));

        List<RequestGroup> groups = ctl.groups();
        assertEquals(2, groups.size());

        assertTrue(groups.getFirst().name().startsWith("default"));
        assertEquals(2, groups.getFirst().requests().size());
        assertEquals(req1, groups.get(0).requests().get(0));
        assertEquals(req2, groups.get(0).requests().get(1));

        assertEquals("custom-group", groups.get(1).name());
        assertEquals(1, groups.get(1).requests().size());
        assertEquals(req3, groups.get(1).requests().getFirst());

        ExecutionPlan plan = ctl.buildPlan();
        assertEquals(3, plan.totalRequestCount());
        assertEquals(3, plan.allRequests().size());
    }

    @Test
    public void testActivateAsyncMode() {
        ClientService service = new ClientService();
        ClientCtl ctl = new ClientCtl(service);

        assertFalse(ctl.isAsync());
        assertFalse(ctl.buildPlan().isAsync());

        ctl.activateAsync();

        assertTrue(ctl.isAsync());
        assertTrue(ctl.buildPlan().isAsync());
    }

    @Test
    public void testDefaultThreadCountAndOverrideViaController() {
        ClientService service = new ClientService();
        assertEquals(ClientService.DEFAULT_THREAD_COUNT, service.getDefaultThreadCount());
        assertEquals(10, service.getDefaultThreadCount());

        ClientCtl ctl = new ClientCtl(service);
        assertEquals(-1, ctl.threadCount());
        assertEquals(-1, ctl.buildPlan().getThreadCount());

        ctl.threadCount(8);
        assertEquals(8, ctl.threadCount());
        assertEquals(8, ctl.buildPlan().getThreadCount());

        assertThrows(IllegalArgumentException.class, () -> ctl.threadCount(0));
        assertThrows(IllegalArgumentException.class, () -> ctl.threadCount(-5));
    }

    @Test
    public void testSynchronizeLastBytesOption() {
        ClientService service = new ClientService();
        ClientCtl ctl = new ClientCtl(service);

        assertFalse(ctl.isSynchronizeLastBytes());
        assertFalse(ctl.buildPlan().isSynchronizeLastBytes());

        ctl.synchronizeLastBytes();

        assertTrue(ctl.isSynchronizeLastBytes());
        assertTrue(ctl.buildPlan().isSynchronizeLastBytes());
    }

    @Test
    public void testClientCtlDoesNotOwnExecutorService() {
        for (Field field : ClientCtl.class.getDeclaredFields()) {
            assertFalse(Executor.class.isAssignableFrom(field.getType()),
                    "ClientCtl must not own Executor fields: " + field.getName());
            assertFalse(ExecutorService.class.isAssignableFrom(field.getType()),
                    "ClientCtl must not own ExecutorService fields: " + field.getName());
        }

        boolean referencesExecutorService = Arrays.stream(ClientCtl.class.getDeclaredMethods())
                .anyMatch(m -> ExecutorService.class.isAssignableFrom(m.getReturnType()));
        assertFalse(referencesExecutorService, "ClientCtl should not return or manage ExecutorService");
    }

    @Test
    public void testExecuteDelegatesToClientService() {
        ClientService service = new ClientService();
        ClientCtl ctl = new ClientCtl(service);

        ExecutionResult result = ctl.execute();
        assertNotNull(result);
        assertEquals(0, result.size());
        assertTrue(result.futures().isEmpty());
    }
}
