package io.faultline.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

public class CoreTest {

    @Test
    public void percentileUsesNearestRank() {
        double[] a = new double[100];
        for (int i = 0; i < 100; i++) a[i] = i + 1;
        assertEquals(50, Stats.percentile(a, 50), 0);
        assertEquals(95, Stats.percentile(a, 95), 0);
        assertEquals(99, Stats.percentile(a, 99), 0);
        assertEquals(100, Stats.percentile(a, 100), 0);
    }

    @Test
    public void summarizeHandlesEmptyAndSingle() {
        assertEquals(0, Stats.summarize(new double[0]).count());
        Stats.Summary s = Stats.summarize(new double[] {7});
        assertEquals(7, s.p99(), 0);
        assertEquals(7, s.mean(), 0);
    }

    @Test
    public void hashingIsDeterministicAndRoughlyUniform() {
        assertEquals(Hashing.unit(1, "f", "o1", "1"), Hashing.unit(1, "f", "o1", "1"), 0);
        assertNotEquals(Hashing.unit(1, "f", "o1", "1"), Hashing.unit(2, "f", "o1", "1"), 0);
        assertNotEquals(Hashing.unit(1, "f", "o1", "1"), Hashing.unit(1, "f", "o1", "2"), 0);
        double sum = 0;
        int below = 0, n = 20_000;
        for (int i = 0; i < n; i++) {
            double u = Hashing.unit(42, "fault", "o" + i, "1");
            assertTrue(u >= 0 && u < 1);
            sum += u;
            if (u < 0.3) below++;
        }
        assertEquals(0.5, sum / n, 0.01);
        assertEquals(0.3, below / (double) n, 0.01);
    }

    @Test
    public void attemptsAreIndependentDraws() {
        // the retry maths depends on this: P(fail twice) must be p*p, not p
        int both = 0, n = 20_000;
        for (int i = 0; i < n; i++) {
            if (Hashing.unit(7, "f", "o" + i, "1") < 0.3 && Hashing.unit(7, "f", "o" + i, "2") < 0.3) both++;
        }
        assertEquals(0.09, both / (double) n, 0.012);
    }

    @Test
    public void jsonRoundTrip() {
        String text = "{\"a\":1,\"b\":[true,null,\"x\\n\\\"y\\\"\",2.5],\"c\":{\"d\":-3}}";
        Map<String, Object> m = Json.parseObject(text);
        assertEquals(1L, m.get("a"));
        assertEquals(-3L, Json.map(m, "c").get("d"));
        List<Object> b = Json.list(m, "b");
        assertEquals("x\n\"y\"", b.get(2));
        assertEquals(m, Json.parseObject(Json.write(m)));
    }

    @Test
    public void jsonWritesNonFiniteNumbersAsNull() {
        assertEquals("[null,1.5]", Json.write(List.of(Double.NaN, 1.5)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void jsonRejectsTrailingGarbage() {
        Json.parse("{} x");
    }
}
