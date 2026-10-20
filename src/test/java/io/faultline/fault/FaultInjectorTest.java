package io.faultline.fault;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.faultline.fault.FaultSpec.Phase;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class FaultInjectorTest {

    private static FaultSpec error(String id, double p) {
        return new FaultSpec(id, FaultType.ERROR, "/charge", p, 503, Phase.BEFORE, 0, 0);
    }

    @Test
    public void sameSeedSameScheduleRegardlessOfCallOrder() {
        FaultInjector a = new FaultInjector(), b = new FaultInjector();
        a.configure(9, List.of(error("e", 0.3)));
        b.configure(9, List.of(error("e", 0.3)));
        List<Boolean> forward = new ArrayList<>(), backward = new ArrayList<>();
        for (int i = 0; i < 500; i++) forward.add(a.decide("/charge", "o" + i, 1, 0).errorStatus() > 0);
        for (int i = 499; i >= 0; i--) backward.add(0, b.decide("/charge", "o" + i, 1, 0).errorStatus() > 0);
        assertEquals(forward, backward);
    }

    @Test
    public void probabilityIsHonoured() {
        FaultInjector f = new FaultInjector();
        f.configure(3, List.of(error("e", 0.25)));
        int hit = 0, n = 10_000;
        for (int i = 0; i < n; i++) if (f.decide("/charge", "o" + i, 1, 0).errorStatus() == 503) hit++;
        assertEquals(0.25, hit / (double) n, 0.015);
    }

    @Test
    public void routeAndWindowAreRespected() {
        FaultInjector f = new FaultInjector();
        f.configure(1, List.of(new FaultSpec("w", FaultType.ERROR, "/charge", 1.0, 503, Phase.BEFORE, 1000, 2000)));
        assertEquals(0, f.decide("/charge", "o", 1, 999).errorStatus());
        assertEquals(503, f.decide("/charge", "o", 1, 1000).errorStatus());
        assertEquals(0, f.decide("/charge", "o", 1, 2000).errorStatus());
        assertEquals(0, f.decide("/reserve", "o", 1, 1500).errorStatus());
    }

    @Test
    public void faultsCombine() {
        FaultInjector f = new FaultInjector();
        f.configure(1, List.of(
                new FaultSpec("slow", FaultType.LATENCY, "*", 1.0, 100, Phase.BEFORE, 0, 0),
                new FaultSpec("drop", FaultType.DROP, "*", 1.0, 0, Phase.AFTER, 0, 0),
                new FaultSpec("crash", FaultType.CRASH, "*", 1.0, 0, Phase.BEFORE, 5, 10)));
        FaultInjector.Decision d = f.decide("/x", "o", 1, 0);
        assertEquals(100, d.beforeDelayMs());
        assertTrue(d.dropAfter());
        assertFalse(d.dropBefore());
        assertEquals(List.of("slow", "drop"), d.applied()); // CRASH is scheduled, never per-request
    }
}
