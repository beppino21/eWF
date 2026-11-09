package it.eone.ewf.sap.migration;

import it.eone.ewf.engine.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static it.eone.ewf.sap.migration.LegacyConfig.*;

/**
 * Trasforma la configurazione /EWF/ nel modello del nuovo motore. Non tocca il DB: produce un piano
 * e l'elenco delle osservazioni. Regole concordate:
 *  - 99999 = fine processo; 00000 = errore (non importato in silenzio);
 *  - WF_DUMMY = nessun aggancio; WF_USER_TASK = RULES; WF_USER_TASK_MIGO = STARTER; altre FM = HANDLER;
 *  - codice di rilascio (Z_WF_SET_RIL): un unico rilascio a fine processo, come oggi;
 *  - regole USER_TASK portate SENZA il criterio team (il team va ripensato);
 *  - niente password, niente processi in corso, niente storico.
 */
public final class LegacyMapper {

    public static final String DUMMY = "/EWF/WF_DUMMY";
    public static final String USER_TASK_FM = "/EWF/WF_USER_TASK";
    public static final String USER_TASK_MIGO_FM = "/EWF/WF_USER_TASK_MIGO";
    public static final String FALLBACK_TEAM = "WFADM";

    /**
     * excludedProcesses: codici esatti oppure prefissi terminati da '*' (es. "UC_*").
     * Default del sistema di sviluppo: trasferte (progetto dedicato) e UC_* (dimostrazione Universita' Cattolica).
     */
    public record Options(Set<String> excludedProcesses) {
        public static Options defaults() { return new Options(Set.of("EONE_TRASFERTE", "UC_*")); }

        public boolean excludes(String process) {
            for (String e : excludedProcesses)
                if (e.endsWith("*") ? process.startsWith(e.substring(0, e.length() - 1)) : process.equals(e)) return true;
            return false;
        }
    }

    private final Options options;
    private final MigrationPlan plan = new MigrationPlan();
    /** "processo|ruolo" (processo vuoto = tutti) -> modalita' speciale da regole SY_START / SY_UNAME. */
    private final Map<String, AssigneeMode> specialRoles = new HashMap<>();

    public LegacyMapper(Options options) {
        this.options = options;
    }

    public MigrationPlan map(LegacyConfig cfg) {
        for (String t : LegacyConfig.TABLES) plan.readCounts.put(t.substring(3), cfg.rows(t).size());
        mapOrganization(cfg);
        mapProcesses(cfg);
        reportNotMigrated(cfg);
        checkRuleCoverage();
        return plan;
    }

    /**
     * Task assegnati per regole: deve esistere almeno una regola (del processo o generica) per il ruolo,
     * con un utente attivo di peso sufficiente; altrimenti il task finirebbe al team di riserva.
     * Le condizioni (gruppo acquisti, divisione, importo, date) non sono valutate: e' un controllo minimo.
     */
    private void checkRuleCoverage() {
        Map<String, MigrationPlan.User> users = new HashMap<>();
        for (MigrationPlan.User u : plan.users) users.put(u.login(), u);
        for (ProcessVersion v : plan.versions)
            for (StepDef st : v.steps())
                for (TaskDef t : st.tasks()) {
                    if (t.assigneeMode() != AssigneeMode.RULES) continue;
                    String where = v.processTypeCode() + " step " + st.stepNo() + " ruolo " + t.roleCode();
                    List<MigrationPlan.Rule> rs = plan.rules.stream()
                            .filter(r -> r.role().equals(t.roleCode())
                                    && (r.processType() == null || r.processType().equals(v.processTypeCode())))
                            .toList();
                    if (rs.isEmpty()) {
                        plan.issues.add(Issue.warn("Copertura", where,
                                "Nessuna regola USER_TASK per il ruolo: il task andrebbe al team " + FALLBACK_TEAM));
                        continue;
                    }
                    List<MigrationPlan.User> active = rs.stream().map(r -> users.get(r.userLogin()))
                            .filter(u -> u != null && u.active()).distinct().toList();
                    if (active.isEmpty()) {
                        plan.issues.add(Issue.warn("Copertura", where,
                                "Le regole indicano solo utenti annullati: il task andrebbe al team " + FALLBACK_TEAM));
                    } else if (active.stream().allMatch(u -> effectiveWeight(u, v.processTypeCode()) < t.requiredWeight())) {
                        String who = active.stream().map(u -> u.login() + " (peso " + effectiveWeight(u, v.processTypeCode()) + ")")
                                .collect(Collectors.joining(", "));
                        plan.issues.add(Issue.warn("Copertura", where, "Peso richiesto " + t.requiredWeight()
                                + ", le regole indicano: " + who
                                + ". Il task viene assegnato ma l'utente potra' solo spostarlo (MOVE): errore di configurazione"));
                    }
                }
    }

    private int effectiveWeight(MigrationPlan.User u, String process) {
        return plan.weights.stream().filter(w -> w.userLogin().equals(u.login()) && w.processType().equals(process))
                .mapToInt(MigrationPlan.Weight::weight).findFirst().orElse(u.weight());
    }

    // =====================================================================
    // Organizzazione
    // =====================================================================

    private void mapOrganization(LegacyConfig cfg) {
        for (Map<String, String> r : cfg.rows(ROLES))
            plan.roles.put(r.get("Z_WF_RUOLO"), r.get("Z_WF_TXTRUOLO"));
        for (Map<String, String> r : cfg.rows(TEAM))
            plan.teams.put(r.get("TEAMCODI"), r.get("TEAMDESCR"));
        plan.teams.putIfAbsent(FALLBACK_TEAM, "Amministratori workflow (riserva)");
        plan.issues.add(Issue.warn("Organizzazione", FALLBACK_TEAM,
                "Team di riserva creato senza membri: aggiungere almeno un amministratore"));

        Set<String> logins = new HashSet<>();
        for (Map<String, String> r : cfg.rows(USER0)) {
            String login = r.get("UNAME");
            if (login.isBlank()) continue;
            String name = (r.getOrDefault("NAME1", "") + " " + r.getOrDefault("NAME2", "")).trim();
            String sap = r.getOrDefault("UNAME_SAP", "");
            boolean active = !"X".equals(r.get("ANNULLATO"));
            plan.users.add(new MigrationPlan.User(login, name.isEmpty() ? login : name,
                    sap.isBlank() ? login : sap, intOf(r.get("PUNTEGGIO")), blankToNull(r.get("MAIL")), active));
            logins.add(login);
            String team = r.getOrDefault("TEAM", "");
            if (!team.isBlank()) {
                plan.teams.putIfAbsent(team, team);
                plan.members.add(new MigrationPlan.Member(team, login));
            }
        }

        int excludedRules = 0;
        for (Map<String, String> r : cfg.rows(USER_TASK)) {
            String src = "USER_TASK " + r.get("PROCESSO") + "/" + r.get("RUOLO") + "/" + r.get("UTENTE_TASK");
            String user = r.get("UTENTE_TASK");
            String proc = r.getOrDefault("PROCESSO", "");
            if (!proc.isBlank() && options.excludes(proc)) {
                excludedRules++;
                continue;
            }
            if (user.equals("SY_UNAME") || user.equals("SY-UNAME") || user.equals("SY_START") || user.equals("SY-START")) {
                AssigneeMode m = user.contains("START") ? AssigneeMode.STARTER : AssigneeMode.ACTOR;
                specialRoles.put(r.get("PROCESSO") + "|" + r.get("RUOLO"), m);
                plan.issues.add(Issue.info("Regole", src, "Regola " + user + ": i task con ruolo " + r.get("RUOLO")
                        + " vengono assegnati in modalita' " + m + " (" + (m == AssigneeMode.STARTER
                        ? "a chi ha avviato il processo" : "a chi ha eseguito il task precedente") + ")"));
                continue;
            }
            if (!logins.contains(user)) {
                plan.issues.add(Issue.warn("Regole", src, "Utente " + user + " non presente in USER0: regola scartata"));
                continue;
            }
            if (!r.getOrDefault("TEAM", "").isBlank())
                plan.issues.add(Issue.info("Regole", src, "Criterio team '" + r.get("TEAM") + "' non migrato (team da ripensare)"));
            if (decimalOf(r.get("IMPBUDGET")).signum() > 0)
                plan.issues.add(Issue.warn("Regole", src, "Budget " + r.get("IMPBUDGET")
                        + " non migrato: controllo budget non ancora disponibile nel motore"));
            BigDecimal from = decimalOf(r.get("IMPORTO_DA"));
            BigDecimal to = decimalOf(r.get("IMPORTO_A"));
            // 0..0 e 0..9.999.999.999 (o comunque un massimo "infinito") = nessuna fascia: qualsiasi valore.
            // Normalizzarle conta: una regola con fascia non si applica finche' il valore del documento non e' noto.
            boolean noRange = from.signum() == 0 && (to.signum() == 0 || to.compareTo(UNLIMITED) >= 0);
            plan.rules.add(new MigrationPlan.Rule(blankToNull(r.get("PROCESSO")), r.get("RUOLO"),
                    blankToNull(r.get("EKGRP")), blankToNull(r.get("WERKS")), blankToNull(r.get("LGORT")),
                    noRange ? null : from, noRange ? null : to, noRange ? null : blankToNull(r.get("WAERS")),
                    user, dateOf(r.get("DATADA")), dateOf(r.get("DATAA")), src));
            plan.roles.putIfAbsent(r.get("RUOLO"), r.get("RUOLO"));
        }

        if (excludedRules > 0)
            plan.issues.add(Issue.info("Regole", "USER_TASK", excludedRules
                    + " regole di processi esclusi non migrate"));

        for (Map<String, String> r : cfg.rows(SOSTITUTI)) {
            String who = r.get("USER_ATT"), sub = r.get("USER_SOST");
            LocalDate from = dateOf(r.get("DATA_DA")), to = dateOf(r.get("DATA_A"));
            String src = "SOSTITUTI " + who + " -> " + sub;
            if (!logins.contains(who) || !logins.contains(sub)) {
                plan.issues.add(Issue.warn("Sostituti", src, "Utente non presente in USER0: scartato"));
            } else if (from == null || to == null || to.isBefore(from) || who.equals(sub)) {
                plan.issues.add(Issue.warn("Sostituti", src, "Periodo non valido o auto-sostituzione: scartato"));
            } else {
                plan.substitutions.add(new MigrationPlan.Substitution(who, sub, from, to));
            }
        }

        for (Map<String, String> r : cfg.rows(USR_AUTH)) {
            if ("X".equals(r.get("ANNULLATO"))) continue;
            if (!logins.contains(r.get("UNAME"))) {
                plan.issues.add(Issue.warn("Pesi", "USR_AUTH " + r.get("UNAME"), "Utente non presente in USER0: scartato"));
                continue;
            }
            plan.weights.add(new MigrationPlan.Weight(r.get("UNAME"), r.get("PROCID"), intOf(r.get("LIV_AUT"))));
        }
    }

    // =====================================================================
    // Processi
    // =====================================================================

    private void mapProcesses(LegacyConfig cfg) {
        Map<String, String> typeTexts = cfg.rows(PROCTYPE).stream()
                .collect(Collectors.toMap(r -> r.get("Z_WF_PROCESSO"), r -> r.getOrDefault("TEXT", ""), (a, b) -> a));
        Map<String, List<Map<String, String>>> tasksByProcess = cfg.rows(TASK).stream()
                .collect(Collectors.groupingBy(r -> r.get("Z_WF_PROCESSO"), TreeMap::new, Collectors.toList()));

        long versionSeq = 0;
        for (Map<String, String> pr : cfg.rows(PROCESSO)) {
            String code = pr.get("Z_WF_PROCESSO");
            if (options.excludes(code)) {
                plan.excludedProcesses.add(code);
                continue;
            }
            List<Map<String, String>> rows = tasksByProcess.getOrDefault(code, List.of());
            if (rows.isEmpty()) {
                plan.issues.add(Issue.warn("Processi", code, "Nessuno step definito in TASK: processo non migrato"));
                continue;
            }
            String docType = pr.getOrDefault("Z_WF_TIPODOC", "");
            if (docType.isBlank()) {
                docType = "N/D";
                plan.issues.add(Issue.warn("Processi", code, "Tipo documento vuoto: impostato N/D"));
            }
            String text = firstNonBlank(pr.get("Z_WF_TEXT"), typeTexts.get(code), code);
            plan.types.add(new ProcessType(code, text, docType, "X".equals(pr.get("Z_WF_SOSPESO")),
                    "X".equals(pr.get("Z_WF_AUTO_OK")), "X".equals(pr.get("Z_WF_NOFIRSTSTEP"))));
            for (String f : List.of("Z_NO_WF00", "Z_NO_MULTI", "Z_BLOCCAMODIFICA", "Z_WF_FITTIZIO"))
                if ("X".equals(pr.get(f)))
                    plan.issues.add(Issue.info("Processi", code, f + " = X: opzione SAP GUI non migrata"));

            String endFm = handlerOrNull(pr.get("Z_WF_FM_END_PROC"));
            if (endFm != null) useHandler(endFm, "PROCESS_END", code + " (fine processo)");

            List<StepDef> steps = mapSteps(code, rows);
            Map<String, String> endConfig = releaseCode(code, rows, endFm);
            try {
                plan.versions.add(new ProcessVersion(++versionSeq, code, 1, endFm, endConfig, steps));
            } catch (IllegalArgumentException e) {
                plan.issues.add(Issue.error("Processi", code, "Definizione non valida: " + e.getMessage()));
            }
        }
        for (String orphan : tasksByProcess.keySet())
            if (cfg.rows(PROCESSO).stream().noneMatch(p -> p.get("Z_WF_PROCESSO").equals(orphan)))
                plan.issues.add(Issue.warn("Processi", orphan, "Step in TASK senza testata in PROCESSO: ignorati"));
    }

    private List<StepDef> mapSteps(String process, List<Map<String, String>> rows) {
        Map<Integer, List<Map<String, String>>> byStep = rows.stream()
                .collect(Collectors.groupingBy(r -> intOf(r.get("Z_WF_STEP")), TreeMap::new, Collectors.toList()));
        long taskSeq = 0;
        List<StepDef> steps = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, String>>> e : byStep.entrySet()) {
            int stepNo = e.getKey();
            List<Map<String, String>> subs = e.getValue().stream()
                    .sorted(Comparator.comparingInt(r -> intOf(r.get("Z_WF_SUBOP")))).toList();
            Map<String, String> head = subs.get(0);
            String where = process + " step " + stepNo;

            Integer nextOk = transition(where, "STEP_OK", head.get("Z_WF_STEP_OK"));
            Integer nextKo = transition(where, "STEP_KO", head.get("Z_WF_STEP_KO"));
            for (Map<String, String> s : subs)
                if (!s.get("Z_WF_STEP_OK").equals(head.get("Z_WF_STEP_OK")) || !s.get("Z_WF_STEP_KO").equals(head.get("Z_WF_STEP_KO")))
                    plan.issues.add(Issue.warn("Processi", where, "SUBOP con transizioni diverse: usate quelle del SUBOP "
                            + head.get("Z_WF_SUBOP")));

            BigDecimal limit = decimalOf(head.get("Z_WF_LIMIT"));
            Money min = null;
            if (limit.signum() > 0) {
                String cur = head.getOrDefault("Z_WF_WAERS", "");
                if (cur.isBlank()) plan.issues.add(Issue.error("Processi", where, "Soglia " + limit + " senza divisa"));
                else min = new Money(limit, cur);
            }

            List<TaskDef> tasks = new ArrayList<>();
            boolean first = subs.stream().anyMatch(r -> "X".equals(r.get("Z_WF_FIRST_STEP")));
            for (Map<String, String> r : subs) tasks.add(mapTask(++taskSeq, where, r, first));
            steps.add(new StepDef(stepNo, first, JoinPolicy.ALL, nextOk, nextKo, min, tasks));
        }
        if (steps.stream().noneMatch(StepDef::first))
            plan.issues.add(Issue.error("Processi", process, "Nessuno step marcato come primo (Z_WF_FIRST_STEP)"));
        return steps;
    }

    private TaskDef mapTask(long id, String where, Map<String, String> r, boolean firstStep) {
        int subop = intOf(r.get("Z_WF_SUBOP"));
        String who = where + (subop > 0 ? " subop " + subop : "");

        AssigneeMode mode;
        String assigneeHandler = null;
        String thisUser = r.getOrDefault("Z_WF_THIS_USER", "");
        if (thisUser.isBlank()) {
            mode = AssigneeMode.RULES;
            plan.issues.add(Issue.warn("Processi", who, "THIS_USER vuoto (oggi errore 'Manca FM USER'): impostato RULES"));
        } else if (thisUser.equals(USER_TASK_FM)) {
            mode = special(r.get("Z_WF_PROCESSO"), r.get("Z_WF_RUOLO"));
            if (mode == AssigneeMode.ACTOR && firstStep) {
                // SY_UNAME sul primo step = chi avvia. In ACTOR, un rifiuto che torna al primo step
                // lo assegnerebbe a chi ha rifiutato invece che al richiedente.
                mode = AssigneeMode.STARTER;
                plan.issues.add(Issue.info("Processi", who, "SY_UNAME sul primo step: assegnato a chi avvia il processo"
                        + " (anche quando un rifiuto lo riporta qui)"));
            }
        } else if (thisUser.equals(USER_TASK_MIGO_FM)) {
            mode = AssigneeMode.STARTER;
            plan.issues.add(Issue.info("Processi", who, "WF_USER_TASK_MIGO sostituita da assegnazione a chi avvia il processo"));
        } else {
            mode = AssigneeMode.HANDLER;
            assigneeHandler = thisUser;
            useHandler(thisUser, "ASSIGNEE", who);
        }

        List<HookDef> hooks = new ArrayList<>();
        hook(hooks, HookEvent.BEFORE_ACTION, r.get("Z_WF_FM_EXANTE"), null, who);
        hook(hooks, HookEvent.ON_OK, r.get("Z_WF_FM_OK"), null, who);
        hook(hooks, HookEvent.ON_KO, r.get("Z_WF_FM_KO"), null, who);
        hook(hooks, HookEvent.ON_ACTIVATE, r.get("Z_WF_FM_EXPOST"), null, who);
        hook(hooks, HookEvent.BUTTON, r.get("Z_WF_FM_BUTTON01"), blankToNull(r.get("Z_WF_LABEL_BUT01")), who);
        if (!r.getOrDefault("Z_WF_FM_NEXTSTEP", "").isBlank())
            plan.issues.add(Issue.warn("Processi", who, "FM_NEXTSTEP " + r.get("Z_WF_FM_NEXTSTEP")
                    + " non supportata: le transizioni restano quelle definite"));
        if (!r.getOrDefault("Z_WF_RESET_RIL", "").isBlank())
            plan.issues.add(Issue.warn("Processi", who, "RESET_RIL " + r.get("Z_WF_RESET_RIL") + " non migrato"));

        String role = blankToNull(r.get("Z_WF_RUOLO"));
        if (role != null) plan.roles.putIfAbsent(role, role);
        String text = firstNonBlank(r.get("Z_WF_TEXT"), who, who);
        return new TaskDef(id, subop, text.length() > 80 ? text.substring(0, 80) : text, role,
                intOf(r.get("Z_WF_LIV")), mode, assigneeHandler, false, null, PreviewMode.NAME,
                "X".equals(r.get("Z_NO_WEB")) ? Interaction.SAPGUI_ONLY : Interaction.WEB,
                blankToNull(r.get("ZWF_TASK_PARAM")), hooks);
    }

    /** RULES, salvo regole SY_START / SY_UNAME per quel ruolo (nel processo o in tutti i processi). */
    private AssigneeMode special(String process, String role) {
        AssigneeMode m = specialRoles.get(process + "|" + role);
        if (m == null) m = specialRoles.get("|" + role);
        return m == null ? AssigneeMode.RULES : m;
    }

        private void hook(List<HookDef> hooks, HookEvent ev, String fm, String label, String where) {
        String h = handlerOrNull(fm);
        if (h == null) return;
        hooks.add(new HookDef(ev, 1, h, Map.of(), label));
        useHandler(h, "TASK_ACTION", where + " " + ev);
    }

    /** Codice di rilascio: oggi applicato una volta a fine processo; lo diventa parametro dell'azione di fine. */
    private Map<String, String> releaseCode(String process, List<Map<String, String>> rows, String endFm) {
        TreeMap<Integer, String> codes = new TreeMap<>();
        for (Map<String, String> r : rows) {
            String c = r.getOrDefault("Z_WF_SET_RIL", "");
            if (!c.isBlank()) codes.put(intOf(r.get("Z_WF_STEP")), c);
        }
        if (codes.isEmpty()) return Map.of();
        Set<String> distinct = new TreeSet<>(codes.values());
        String code = codes.lastEntry().getValue();
        if (distinct.size() > 1)
            plan.issues.add(Issue.warn("Processi", process, "Codici di rilascio diversi " + distinct
                    + ": usato " + code + " (dello step " + codes.lastKey() + ")"));
        if (endFm == null)
            plan.issues.add(Issue.warn("Processi", process, "Codice di rilascio " + code
                    + " presente ma nessuna FM di fine processo: non verra' applicato"));
        return Map.of("Z_WF_SET_RIL", code);
    }

    private Integer transition(String where, String field, String raw) {
        int n = intOf(raw);
        if (n == 99999) return null;
        if (n == 0) plan.issues.add(Issue.warn("Processi", where, field
                + " = 00000: transizione non valida, da correggere in /EWF/TASK (la validazione blocca l'attivazione)"));
        return n;
    }

    private void reportNotMigrated(LegacyConfig cfg) {
        if (!cfg.rows(TASKOPZ).isEmpty())
            plan.issues.add(Issue.info("Non migrato", "TASKOPZIONI",
                    cfg.rows(TASKOPZ).size() + " opzioni di task: non ancora gestite dal motore"));
        if (!cfg.rows(CLI_USER).isEmpty())
            plan.issues.add(Issue.info("Non migrato", "WF_CLI_USER", cfg.rows(CLI_USER).size() + " eccezioni di assegnazione"));
        if (!cfg.rows(CDO_USERGRP).isEmpty())
            plan.issues.add(Issue.info("Non migrato", "CDO_USERGRP", cfg.rows(CDO_USERGRP).size() + " righe gruppi utenti"));
        if (!cfg.rows(CDO_AUTH).isEmpty())
            plan.issues.add(Issue.info("Non migrato", "CDO_AUTH", cfg.rows(CDO_AUTH).size() + " righe autorizzazioni team"));
        for (String p : plan.excludedProcesses)
            plan.issues.add(Issue.info("Esclusi", p, "Processo escluso per configurazione"));
    }

    private void useHandler(String code, String contract, String where) {
        plan.handlers.putIfAbsent(code, contract);
        plan.handlerUsage.computeIfAbsent(code, k -> new TreeSet<>()).add(where);
    }

    // ---------------------------------------------------------------- conversioni

    private static String handlerOrNull(String fm) {
        return fm == null || fm.isBlank() || fm.equals(DUMMY) ? null : fm;
    }

    static int intOf(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return new BigDecimal(s.trim()).intValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Da questo importo in su, il limite superiore di USER_TASK vale "senza limite". */
    static final BigDecimal UNLIMITED = new BigDecimal("999999999");

    static BigDecimal decimalOf(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** Accetta 20261106, 2026-11-06; vuoto o zeri = nessuna data; 99991231 resta valida. */
    static LocalDate dateOf(String s) {
        if (s == null) return null;
        String d = s.replace("-", "").replace(".", "").trim();
        if (d.isEmpty() || d.chars().allMatch(ch -> ch == '0')) return null;
        try {
            return LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return "";
    }
}
