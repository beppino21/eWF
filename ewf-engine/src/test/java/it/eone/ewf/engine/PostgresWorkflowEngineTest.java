package it.eone.ewf.engine;

import it.eone.ewf.engine.EngineException.Code;
import it.eone.ewf.engine.model.*;
import it.eone.ewf.engine.spi.AuditSink;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static it.eone.ewf.engine.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Gli stessi scenari su PostgreSQL, piu' i casi che solo un DB vero puo' mostrare:
 * rollback, approvazioni simultanee, avvii simultanei.
 * Girano solo se EWF_TEST_DB_URL e' impostata, altrimenti vengono saltati.
 */
class PostgresWorkflowEngineTest extends WorkflowEngineContract {

    @Override
    protected Backend newBackend() {
        assumeTrue(PostgresBackend.configured(), "EWF_TEST_DB_URL non impostata: test PostgreSQL saltati");
        return new PostgresBackend();
    }

    @Test
    void erroreDentroLaTransazioneAnnullaTutto() {
        assertThrows(IllegalStateException.class, () -> f.b.tx().inTransaction(() -> {
            f.b.audit().append(new AuditSink.AuditEvent(Instant.now(), "TEST_ROLLBACK", null, "SYSTEM", null, null, Map.of()));
            throw new IllegalStateException("errore simulato");
        }));
        assertEquals(0, ((PostgresBackend) f.b).countAudit("TEST_ROLLBACK"));
    }

    @Test
    void dueApprovazioniSimultaneeUnaSolaPassa() throws Exception {
        for (int round = 0; round < 5; round++) {
            ProcessInstance p = f.startOda("45000009" + round, "1000");
            f.ok(f.openTask(p.id).id, ROSSI);
            TaskInstance t20 = f.openTask(p.id);

            List<Object> results = race(() -> f.ok(t20.id, BIANCHI), () -> f.ok(t20.id, BIANCHI));

            long done = results.stream().filter(r -> r instanceof Requests.Outcome o
                    && o.result() == Requests.Outcome.Result.DONE).count();
            long refused = results.stream().filter(r -> r instanceof EngineException e
                    && (e.code() == Code.CONCURRENT_UPDATE || e.code() == Code.INVALID_STATE)).count();
            assertEquals(1, done, "round " + round + ": " + results);
            assertEquals(1, refused, "round " + round + ": " + results);
            assertEquals(1, f.store.executionsOf(p.id).stream()
                    .filter(x -> x.handlerCode.equals("/EWF/WF_RIL_ODA")).count(), "un solo rilascio");
        }
    }

    @Test
    void dueAvviiSimultaneiSulloStessoDocumento() throws Exception {
        for (int round = 0; round < 5; round++) {
            String ebeln = "45000008" + round;
            f.purchaseOrders.value(ebeln, Money.of("1000", "EUR"));
            Supplier<Object> start = () -> f.engine.start(new Requests.Start(ODA, ebeln, null, ROSSI, null));

            List<Object> results = race(start, start);

            assertEquals(1, results.stream().filter(r -> r instanceof ProcessInstance).count(), results.toString());
            assertEquals(1, results.stream().filter(r -> r instanceof EngineException e
                    && e.code() == Code.DUPLICATE_PROCESS).count(), results.toString());
        }
    }

    @Test
    void dueParalleliApprovatiInsiemeFannoAvanzareIlProcesso() throws Exception {
        parallelRace(JoinPolicy.ALL);
    }

    @Test
    void conJoinAnyIlProcessoAvanzaUnaVoltaSola() throws Exception {
        parallelRace(JoinPolicy.ANY);
    }

    private void parallelRace(JoinPolicy join) throws Exception {
        // l'handler ON_OK trattiene entrambi i thread finche' non sono arrivati tutti e due:
        // cosi' le due chiusure partono nello stesso istante (il caso peggiore per il join)
        CyclicBarrier together = new CyclicBarrier(2);
        f.handlers.register("Z_SYNC", ctx -> {
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return it.eone.ewf.engine.spi.HandlerResult.ok();
        });
        f.handlers.register("Z_DUE_PARERI", ctx -> it.eone.ewf.engine.spi.HandlerResult.users(
                it.eone.ewf.engine.spi.HandlerResult.UserRef.ewf("VERDI"),
                it.eone.ewf.engine.spi.HandlerResult.UserRef.ewf("NERI")));
        f.b.defineType(new ProcessType("PARERI", "Pareri paralleli", "F", false, false, false));
        f.b.activate(new ProcessVersion(1, "PARERI", 1, null, List.of(
                new StepDef(10, true, join, null, null, null, List.of(new TaskDef(1, 0, "Parere", "PARERE", 0,
                        AssigneeMode.HANDLER, "Z_DUE_PARERI", false, null, PreviewMode.NAME, Interaction.WEB, null,
                        List.of(HookDef.of(HookEvent.ON_OK, "Z_SYNC")))))))); 

        for (int round = 0; round < 10; round++) {
            together.reset();
            String key = "P" + round;
            f.purchaseOrders.value(key, Money.of("100", "EUR"));
            ProcessInstance p = f.engine.start(new Requests.Start("PARERI", key, null, ROSSI, null));
            List<TaskInstance> par = f.openTasks(p.id);
            assertEquals(2, par.size());
            TaskInstance a = par.get(0), b = par.get(1);

            List<Object> results = race(() -> f.ok(a.id, a.assigneeId), () -> f.ok(b.id, b.assigneeId));

            assertTrue(results.stream().allMatch(r -> r instanceof Requests.Outcome), results.toString());
            assertEquals(ProcessStatus.COMPLETED, f.status(p.id), "round " + round + ": processo bloccato");
            assertEquals(1, f.b.auditTypes(p.id).stream().filter("PROCESS_COMPLETED"::equals).count(),
                    "round " + round + ": il processo deve avanzare una volta sola");
        }
    }

    @Test
    void versioneConUnRamoRottoNonSiAttivaEResterebbeInBozza() {
        f.b.defineType(new ProcessType("ROTTO", "Ramo mancante", "GEN", false, false, false));
        ProcessVersion good = f.ramiVersion(30);
        // tolgo la variante 30/B: il ramo B non ha piu' dove andare
        ProcessVersion broken = new ProcessVersion(31, "ROTTO", 1, null, good.steps().stream()
                .filter(s -> !(s.stepNo() == 30 && "B".equals(s.option()))).toList());
        it.eone.ewf.engine.jdbc.JdbcException e = assertThrows(it.eone.ewf.engine.jdbc.JdbcException.class,
                () -> f.b.activate(broken));
        assertTrue(e.getMessage().contains("{20=B}"), e.getMessage());
        assertTrue(f.b.defs().activeVersion("ROTTO").isEmpty());
    }

    @Test
    void opzioneVersoUnTipoProcessoInesistenteBloccaLAttivazione() {
        f.b.defineType(new ProcessType("AVVIO_X", "Avvio", "F", false, false, false));
        TaskDef n = new TaskDef(1, 0, "Avvio", null, 0, AssigneeMode.STARTER, null, false, null, PreviewMode.NAME,
                Interaction.WEB, null, List.of()).withOptions(List.of(
                OptionDef.handoff("STD", "Standard", "NON_ESISTE"), OptionDef.reject("RIV", "Rivedi")));
        it.eone.ewf.engine.jdbc.JdbcException e = assertThrows(it.eone.ewf.engine.jdbc.JdbcException.class,
                () -> f.b.activate(new ProcessVersion(1, "AVVIO_X", 1, null,
                        List.of(new StepDef(10, true, JoinPolicy.ALL, null, null, null, List.of(n))))));
        assertTrue(e.getMessage().contains("NON_ESISTE"), e.getMessage());
    }

    /** Esegue due operazioni nello stesso istante; restituisce risultato o eccezione di ciascuna. */
    private static List<Object> race(Supplier<?> a, Supplier<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier go = new CyclicBarrier(2);
        try {
            List<Future<Object>> fs = new ArrayList<>();
            for (Supplier<?> s : List.of(a, b)) {
                fs.add(pool.submit(() -> {
                    go.await();
                    try {
                        return s.get();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
