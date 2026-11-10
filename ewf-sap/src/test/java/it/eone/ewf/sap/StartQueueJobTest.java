package it.eone.ewf.sap;

import it.eone.connect.scheduler.JobResult;
import it.eone.ewf.engine.Requests.EventResult;
import it.eone.ewf.engine.Requests.EventResult.EventStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class StartQueueJobTest {

    private static EventResult r(String id, EventStatus s, String msg) { return new EventResult(id, s, msg, null); }

    private static JobResult drain(int batch, int maxRounds, List<List<EventResult>> rounds, int[] calls) {
        Deque<List<EventResult>> q = new ArrayDeque<>(rounds);
        return StartQueueJob.drain(() -> { calls[0]++; return q.isEmpty() ? List.of() : q.poll(); },
                batch, maxRounds, () -> false);
    }

    @Test
    void codaVuotaNessunoStorico() {
        int[] calls = {0};
        assertEquals(JobResult.Status.IDLE, drain(50, 10, List.of(), calls).status());
        assertEquals(1, calls[0]);
    }

    @Test
    void erroreVinceSullAvvisoEContaTutto() {
        int[] calls = {0};
        JobResult res = drain(50, 10, List.of(List.of(
                r("Q1", EventStatus.DONE, null),
                r("Q2", EventStatus.WARNING, "assegnato al team WFADM"),
                r("Q3", EventStatus.ERROR, "BACKEND_UNAVAILABLE"))), calls);
        assertEquals(JobResult.Status.ERROR, res.status());
        assertTrue(res.message().contains("Q3: BACKEND_UNAVAILABLE"), res.message());
        assertEquals(Map.of("elaborati", 1L, "avvisi", 1L, "errori", 1L), res.counts());
    }

    @Test
    void lottoPienoRileggeFinoAlLimite() {
        List<EventResult> full = IntStream.range(0, 3).mapToObj(i -> r("Q" + i, EventStatus.DONE, null)).toList();
        int[] calls = {0};
        JobResult res = drain(3, 10, List.of(full, full, List.of(r("Q9", EventStatus.DONE, null))), calls);
        assertEquals(3, calls[0], "due lotti pieni e uno parziale");
        assertEquals(7L, res.counts().get("elaborati"));

        int[] capped = {0};
        drain(3, 2, List.of(full, full, full), capped);
        assertEquals(2, capped[0], "mai oltre maxRounds: il resto al giro dopo");
    }
}
