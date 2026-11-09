package it.eone.ewf.engine.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Validazione statica di nodi di scelta e varianti: gli errori si vedono all'attivazione, non in produzione. */
class ProcessVersionBranchesTest {

    private static long ids = 1;

    private static TaskDef t(String text) {
        return new TaskDef(ids++, 0, text, "R", 0, AssigneeMode.STARTER, null, false, null, null, null, null, List.of());
    }

    private static TaskDef node(String... codes) {
        return t("Nodo").withOptions(java.util.Arrays.stream(codes)
                .map(c -> c.startsWith("!") ? OptionDef.reject(c.substring(1), c) : OptionDef.of(c, c)).toList());
    }

    private static StepDef s(int no, Integer ok, Integer ko, TaskDef task) {
        return new StepDef(no, no == 10, JoinPolicy.ALL, ok, ko, null, List.of(task));
    }

    private static StepDef v(int no, String option, Integer ok, Integer ko) {
        return new StepDef(no, false, JoinPolicy.ALL, ok, ko, null, List.of(t("Variante " + option)), option);
    }

    private static List<String> errors(ProcessVersion pv) {
        return pv.problems().stream().filter(p -> p.severity().equals("ERROR")).map(ProcessVersion.Problem::problem).toList();
    }

    @Test
    void processoCorrettoNessunErrore() {
        ProcessVersion pv = new ProcessVersion(1, "X", 1, null, List.of(
                s(10, 20, null, t("Avvio")),
                s(20, 30, null, node("A", "B", "!RIF")),
                v(30, "A", 50, 20), v(30, "B", 40, 20), v(40, "B", 50, 30),
                s(50, null, 20, t("Chiusura"))));
        assertEquals(List.of(), pv.problems());
    }

    @Test
    void sovrascritturaSoloPerLeOpzioniScelte() {
        ProcessVersion pv = new ProcessVersion(1, "X", 1, null, List.of(
                s(10, 20, null, t("Avvio")), s(20, 30, null, node("A", "B")),
                s(30, null, null, t("Comune")), v(30, "B", null, null)));
        assertNull(pv.resolve(30, Set.of("A")).orElseThrow().option(), "A non ha variante: tronco");
        assertEquals("B", pv.resolve(30, Set.of("B")).orElseThrow().option());
        assertEquals(20, pv.nodeOf("B").orElseThrow());
    }

    @Test
    void ramoSenzaStepDiDestinazione() {
        ProcessVersion pv = new ProcessVersion(1, "X", 1, null, List.of(
                s(10, 20, null, t("Avvio")), s(20, 30, null, node("A", "B")), v(30, "A", null, 20)));
        List<String> e = errors(pv);
        assertEquals(1, e.size(), e.toString());
        assertTrue(e.get(0).contains("{20=B}"), "il ramo B non ha uno step 30: " + e);
    }

    @Test
    void codiceOpzioneRipetutoInDueNodi() {
        ProcessVersion pv = new ProcessVersion(1, "X", 1, null, List.of(
                s(10, 20, null, t("Avvio")), s(20, 30, null, node("A", "B")), s(30, null, null, node("A", "C"))));
        assertTrue(errors(pv).get(0).contains("opzione A gia' usata"));
    }

    @Test
    void variantiDiDueNodiSulloStessoStepSonoAmbigue() {
        ProcessVersion pv = new ProcessVersion(1, "X", 1, null, List.of(
                s(10, 20, null, t("Avvio")), s(20, 30, null, node("A", "B")), s(30, 40, null, node("C", "D")),
                s(40, null, null, t("Comune")), v(40, "A", null, null), v(40, "C", null, null)));
        assertTrue(errors(pv).stream().anyMatch(x -> x.contains("piu' varianti")), errors(pv).toString());
    }

    @Test
    void nodoDiSceltaConTaskParalleliEVarianteOrfana() {
        StepDef parallel = new StepDef(20, false, JoinPolicy.ALL, null, null, null, List.of(node("A", "B"), t("Altro")));
        ProcessVersion pv = new ProcessVersion(1, "X", 1, null, List.of(
                s(10, 20, null, t("Avvio")), parallel, v(30, "Z", null, null)));
        List<String> e = errors(pv);
        assertTrue(e.stream().anyMatch(x -> x.contains("un solo task")), e.toString());
        assertTrue(e.stream().anyMatch(x -> x.contains("opzione Z che nessun nodo offre")), e.toString());
    }

    @Test
    void varianteMaiRaggiuntaESoloAvviso() {
        ProcessVersion pv = new ProcessVersion(1, "X", 1, null, List.of(
                s(10, 20, null, t("Avvio")), s(20, 30, null, node("A", "!RIF")),
                s(30, null, 20, t("Comune")), v(40, "A", null, null)));
        assertEquals(List.of(), errors(pv));
        assertTrue(pv.problems().stream().anyMatch(p -> p.problem().contains("mai raggiunta")));
    }

    @Test
    void ilRifiutoTornaIndietroEIlPercorsoVieneComunqueEsplorato() {
        // RIF torna allo step 10: niente fine negativa, ma nessun errore (il ciclo e' finito: stati gia' visti)
        ProcessVersion pv = new ProcessVersion(1, "X", 1, null, List.of(
                s(10, 20, null, t("Avvio")), s(20, null, 10, node("OK1", "!RIF"))));
        assertEquals(List.of(), errors(pv));
        assertEquals(Map.of(), Map.of());
    }
}
