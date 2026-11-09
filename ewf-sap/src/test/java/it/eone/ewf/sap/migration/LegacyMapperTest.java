package it.eone.ewf.sap.migration;

import it.eone.ewf.engine.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mappatura provata sui 49 step REALI di /EWF/TASK del sistema di sviluppo (mandante 200).
 * PROCESSO, USER0 e USER_TASK sono di esempio, costruiti per coprire i casi particolari.
 */
class LegacyMapperTest {

    static LegacyConfig realConfig() throws Exception {
        List<Map<String, String>> task = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                LegacyMapperTest.class.getResourceAsStream("/ewf_task_dev200.tsv"), StandardCharsets.UTF_8))) {
            String[] cols = r.readLine().split("\t", -1);
            String line;
            while ((line = r.readLine()) != null) {
                String[] v = line.split("\t", -1);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < cols.length; i++) row.put(cols[i], i < v.length ? v[i].trim() : "");
                task.add(row);
            }
        }
        Set<String> processes = new TreeSet<>();
        task.forEach(t -> processes.add(t.get("Z_WF_PROCESSO")));
        List<Map<String, String>> processo = new ArrayList<>();
        for (String p : processes) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("Z_WF_PROCESSO", p);
            row.put("Z_WF_TIPODOC", p.equals("EONE_ODA") ? "F" : p.contains("FATT") ? "FA" : "FI_DOC");
            row.put("Z_WF_TEXT", "Processo " + p);
            row.put("Z_WF_FM_END_PROC", p.equals("EONE_ODA") ? "/EWF/WF_RIL_ODA"
                    : p.startsWith("SBLOCCO") ? "/EWF/WF_SBLOCCA_BLOCCO_H" : "");
            processo.add(row);
        }
        return new LegacyConfig()
                .put(LegacyConfig.PROCESSO, processo)
                .put(LegacyConfig.TASK, task)
                .put(LegacyConfig.USER0, List.of(
                        user("ROSSI", "Mario", "Rossi", "", "0", "", ""),
                        user("BIANCHI", "Anna", "Bianchi", "ABIANCHI", "100", "", "ACQ"),
                        user("VERDI", "Luca", "Verdi", "", "200", "", "ACQ"),
                        user("NERI", "Sara", "Neri", "", "200", "", ""),
                        user("EXDIP", "Ex", "Dipendente", "", "100", "X", "")))
                .put(LegacyConfig.TEAM, List.of(Map.of("TEAMCODI", "ACQ", "TEAMDESCR", "Ufficio acquisti")))
                .put(LegacyConfig.USER_TASK, List.of(
                        rule("EONE_ODA", "RESPONSABILE_ACQ", "", "BIANCHI", "0", "0"),
                        rule("EONE_ODA", "RESPONSABILE_ACQ_L1", "", "VERDI", "0", "9999999999"),
                        rule("EONE_ODA", "RESPONSABILE_ACQ_L2", "02", "NERI", "0", "9999999999"),
                        rule("EONE_ODA", "UFFICIO_ACQUISTI", "", "SY_UNAME", "0", "0"),  // come nel sistema reale
                        rule("EONE_ODA", "TESORERIA", "", "FANTASMA", "0", "0"),
                        rule("EONE_TRASFERTE", "RESP_TRASFERTE", "", "VERDI", "0", "0")))
                .put(LegacyConfig.SOSTITUTI, List.of(Map.of("USER_ATT", "BIANCHI", "USER_SOST", "NERI",
                        "DATA_DA", "20261101", "DATA_A", "20261130")))
                .put(LegacyConfig.USR_AUTH, List.of(Map.of("UNAME", "VERDI", "PROCID", "EONE_ODA",
                        "LIV_AUT", "250", "ANNULLATO", "")));
    }

    private static Map<String, String> user(String login, String n1, String n2, String sap, String w, String annullato, String team) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("UNAME", login); m.put("NAME1", n1); m.put("NAME2", n2); m.put("UNAME_SAP", sap);
        m.put("PUNTEGGIO", w); m.put("ANNULLATO", annullato); m.put("TEAM", team); m.put("MAIL", login.toLowerCase() + "@example.it");
        m.put("PWD", "");
        return m;
    }

    private static Map<String, String> rule(String proc, String role, String team, String user, String from, String to) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PROCESSO", proc); m.put("RUOLO", role); m.put("EKGRP", ""); m.put("TEAM", team); m.put("WERKS", "");
        m.put("LGORT", ""); m.put("IMPORTO_DA", from); m.put("IMPORTO_A", to); m.put("WAERS", "EUR");
        m.put("UTENTE_TASK", user); m.put("DATADA", "00000000"); m.put("DATAA", "99991231"); m.put("IMPBUDGET", "0");
        return m;
    }

    /** Tutti i processi tranne le trasferte: per provare la mappatura anche sui casi UC_*. */
    static final LegacyMapper.Options ALL_BUT_TRAVEL = new LegacyMapper.Options(Set.of("EONE_TRASFERTE"));

    private MigrationPlan plan;

    @BeforeEach
    void setUp() throws Exception {
        plan = new LegacyMapper(ALL_BUT_TRAVEL).map(realConfig());
    }

    private ProcessVersion version(String type) {
        return plan.versions.stream().filter(v -> v.processTypeCode().equals(type)).findFirst().orElseThrow();
    }

    private long issues(Issue.Severity s, String text) {
        return plan.issues.stream().filter(i -> i.severity() == s && i.message().contains(text)).count();
    }

    @Test
    void trasferteEsclusePerConfigurazione() {
        assertEquals(List.of("EONE_TRASFERTE"), plan.excludedProcesses);
        assertEquals(13, plan.types.size(), "14 processi in TASK, trasferte escluse");
        assertEquals(13, plan.versions.size());
    }

    @Test
    void defaultEscludeTrasferteEProcessiDimostrativi() throws Exception {
        MigrationPlan p = new LegacyMapper(LegacyMapper.Options.defaults()).map(realConfig());
        assertEquals(9, p.excludedProcesses.size(), "EONE_TRASFERTE + 8 processi UC_*");
        assertTrue(p.excludedProcesses.contains("UC_MULTE"));
        assertTrue(p.rules.stream().noneMatch(r -> "EONE_TRASFERTE".equals(r.processType())),
                "le regole dei processi esclusi non vengono migrate");
        assertEquals(List.of("EONE_FATT_PASSIVA_FI", "EONE_ODA", "SBLOCCO_BLOCCO_A", "SBLOCCO_BLOCCO_R", "SBLOCCO_PAGAMENTO"),
                p.types.stream().map(ProcessType::code).sorted().toList());
        assertTrue(p.issues.stream().noneMatch(i -> i.message().contains("STEP_KO = 00000")));
    }

    @Test
    void odaMigratoComeOggi() {
        ProcessVersion oda = version("EONE_ODA");
        assertEquals(4, oda.steps().size());
        assertEquals(10, oda.firstStep().stepNo());
        assertNull(oda.step(40).orElseThrow().nextOk(), "99999 = fine processo");
        assertEquals(10, oda.step(30).orElseThrow().nextKo());
        assertEquals(Optional.of("/EWF/WF_RIL_ODA"), oda.endHandlerCode());
        assertEquals(Map.of("Z_WF_SET_RIL", "01"), oda.endHandlerConfig(), "rilascio unico a fine processo");

        TaskDef t20 = oda.step(20).orElseThrow().tasks().get(0);
        assertEquals(100, t20.requiredWeight());
        assertEquals(AssigneeMode.RULES, t20.assigneeMode());
        assertEquals(List.of("/EWF/WF_CHECK_LOCK_EKKO"),
                t20.hooks(HookEvent.BEFORE_ACTION).stream().map(HookDef::handlerCode).toList());
        assertEquals(List.of("/EWF/SEND_MAIL_NEW_TASK"),
                t20.hooks(HookEvent.ON_ACTIVATE).stream().map(HookDef::handlerCode).toList());
        assertTrue(t20.hooks(HookEvent.ON_OK).isEmpty(), "WF_DUMMY non diventa un aggancio");
    }

    @Test
    void entrataMerciAssegnataAChiAvvia() {
        TaskDef first = version("UC_EM_APPROVE_ODA").firstStep().tasks().get(0);
        assertEquals(AssigneeMode.STARTER, first.assigneeMode());
        assertEquals(List.of("/EWF/WF_CLEAR_PAYMENT_BLOCK"),
                version("UC_EM_APPROVE_ODA").step(20).orElseThrow().tasks().get(0)
                        .hooks(HookEvent.ON_OK).stream().map(HookDef::handlerCode).toList());
    }

    @Test
    void stepKoZeroSegnalatoComeErrore() {
        // i primi step dei processi UC_* hanno STEP_KO = 00000
        assertEquals(8, issues(Issue.Severity.WARNING, "STEP_KO = 00000"));
        assertEquals(0, version("UC_MULTE").firstStep().nextKo(), "resta 0: lo segnala la validazione");
    }

    @Test
    void catalogoHandlerConContratto() {
        assertEquals("PROCESS_END", plan.handlers.get("/EWF/WF_RIL_ODA"));
        assertEquals("TASK_ACTION", plan.handlers.get("/EWF/WF_FB01_BASIC"));
        assertFalse(plan.handlers.containsKey("/EWF/WF_DUMMY"));
        assertFalse(plan.handlers.containsKey("/EWF/WF_USER_TASK"), "assegnazione standard = funzione nativa");
        assertTrue(plan.handlerUsage.get("/EWF/WF_GET_CONTI_COGE").size() >= 5);
    }

    @Test
    void organizzazioneERegole() {
        assertEquals(5, plan.users.size());
        MigrationPlan.User b = plan.users.stream().filter(u -> u.login().equals("BIANCHI")).findFirst().orElseThrow();
        assertEquals("ABIANCHI", b.sapUser());
        assertEquals("Anna Bianchi", b.displayName());
        assertFalse(plan.users.stream().filter(u -> u.login().equals("EXDIP")).findFirst().orElseThrow().active());
        assertEquals("ROSSI", plan.users.stream().filter(u -> u.login().equals("ROSSI")).findFirst().orElseThrow().sapUser(),
                "senza UNAME_SAP l'utente SAP e' lo stesso login");
        assertTrue(plan.members.contains(new MigrationPlan.Member("ACQ", "VERDI")));

        assertEquals(3, plan.rules.size(), "SY_UNAME diventa una modalita' di assegnazione, utente inesistente scartato");
        assertEquals(1, issues(Issue.Severity.INFO, "Regola SY_UNAME"));
        assertEquals(1, issues(Issue.Severity.INFO, "SY_UNAME sul primo step"),
                "sul primo step SY_UNAME vale 'chi avvia': un rifiuto deve tornare al richiedente, non a chi rifiuta");
        assertEquals(AssigneeMode.STARTER, version("EONE_ODA").firstStep().tasks().get(0).assigneeMode(),
                "UFFICIO_ACQUISTI sul primo step = chi avvia il processo");
        assertEquals(1, issues(Issue.Severity.WARNING, "FANTASMA"));
        assertTrue(plan.issues.stream().anyMatch(i -> i.area().equals("Copertura")
                && i.object().equals("SBLOCCO_PAGAMENTO step 40 ruolo CASSA")), "nessuna regola per CASSA");
        assertEquals(1, issues(Issue.Severity.INFO, "1 regole di processi esclusi"));
        assertEquals(1, issues(Issue.Severity.INFO, "Criterio team '02'"));
        MigrationPlan.Rule zeroRange = plan.rules.stream().filter(r -> r.userLogin().equals("BIANCHI")).findFirst().orElseThrow();
        assertNull(zeroRange.amountFrom(), "fascia 0..0 = nessuna fascia");
        assertNull(zeroRange.validFrom(), "00000000 = nessuna data");
        MigrationPlan.Rule fullRange = plan.rules.stream().filter(r -> r.userLogin().equals("VERDI")).findFirst().orElseThrow();
        assertNull(fullRange.amountTo(), "0..9999999999 = qualsiasi importo: nessuna fascia");
        assertNull(fullRange.currency());

        assertEquals(1, plan.substitutions.size());
        assertEquals(new MigrationPlan.Weight("VERDI", "EONE_ODA", 250), plan.weights.get(0));
    }
}
