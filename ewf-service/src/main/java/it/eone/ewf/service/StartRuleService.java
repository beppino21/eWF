package it.eone.ewf.service;

import it.eone.ewf.engine.EngineException;
import it.eone.ewf.engine.WorkflowEngine;
import it.eone.ewf.engine.jdbc.JdbcException;
import it.eone.ewf.engine.jdbc.JdbcTransactions;
import it.eone.ewf.engine.model.StartRule;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.ServiceException.Code;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Regole di avvio (amministratori): quale processo parte quando arriva un documento nuovo.
 * <p>
 * Una regola vale per un tipo documento e ha delle condizioni sugli attributi del documento ("*" = qualsiasi).
 * Tra le regole attive applicabili vince la priorita' piu' alta, poi quella con piu' condizioni, poi la piu'
 * vecchia: la scelta e' quella del motore ({@link StartRule#choose}), anche nel simulatore.
 */
public final class StartRuleService {

    /** Una regola. startable: il processo e' attivo (non sospeso, con una versione attiva). */
    public record RuleRow(long id, String docType, Map<String, String> conditions, int priority, String processType,
                          String processDescription, boolean active, boolean startable) {}

    /** Una regola da salvare (id null = nuova). */
    public record RuleEdit(Long id, String docType, Map<String, String> conditions, int priority, String processType,
                           boolean active) {}

    /** Una regola nel risultato della simulazione. */
    public record SimLine(RuleRow rule, boolean winner, String excludedBecause) {}

    public record SimResult(String outcome, boolean starts, List<SimLine> lines) {}

    private final JdbcTransactions db;
    private final AdminService admin;
    private final WorkflowEngine engine;

    public StartRuleService(JdbcTransactions db, AdminService admin, WorkflowEngine engine) {
        this.db = db;
        this.admin = admin;
        this.engine = engine;
    }

    private void requireAdmin(long userId) {
        if (!admin.isAdmin(userId))
            throw new ServiceException(Code.NOT_AUTHORIZED, "Funzione riservata agli amministratori");
    }

    public List<Choice> docTypes(long adminId) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT code, description FROM ewf_core.doc_type ORDER BY code");
                 ResultSet rs = ps.executeQuery()) {
                List<Choice> out = new ArrayList<>();
                while (rs.next()) out.add(new Choice(rs.getString(1), rs.getString(2)));
                return out;
            }
        });
    }

    /** Tipi processo di un tipo documento (quelli che una regola puo' avviare). */
    public List<Choice> processTypes(long adminId, String docType) {
        requireAdmin(adminId);
        return db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT code, description FROM ewf_core.process_type WHERE doc_type = ? ORDER BY code")) {
                ps.setString(1, docType);
                List<Choice> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(new Choice(rs.getString(1), rs.getString(2)));
                }
                return out;
            }
        });
    }

    /** Regole (attive e non) di un tipo documento, nell'ordine in cui il motore le considera. */
    public List<RuleRow> rules(long adminId, String docType) {
        requireAdmin(adminId);
        List<RuleRow> out = db.with(c -> rules(c, docType));
        out.sort((a, b) -> {
            if (a.active() != b.active()) return a.active() ? -1 : 1;
            return StartRule.PREFERENCE.compare(model(a), model(b));
        });
        return out;
    }

    /** Chiavi delle condizioni gia' usate (per suggerirle). */
    public List<String> knownKeys(long adminId, String docType) {
        requireAdmin(adminId);
        TreeSet<String> keys = new TreeSet<>();
        for (RuleRow r : rules(adminId, docType)) keys.addAll(r.conditions().keySet());
        return new ArrayList<>(keys);
    }

    public long saveRule(long adminId, RuleEdit e) {
        requireAdmin(adminId);
        if (e.docType() == null || e.docType().isBlank()) throw new ServiceException(Code.INVALID, "Tipo documento obbligatorio");
        if (e.processType() == null || e.processType().isBlank())
            throw new ServiceException(Code.INVALID, "Scegliere il processo da avviare");
        Map<String, String> cond = new LinkedHashMap<>();
        if (e.conditions() != null)
            for (var x : e.conditions().entrySet()) {
                String k = x.getKey() == null ? "" : x.getKey().trim();
                String v = x.getValue() == null ? "" : x.getValue().trim();
                if (k.isEmpty() || v.isEmpty())
                    throw new ServiceException(Code.INVALID, "Ogni condizione ha un attributo e un valore (\"*\" = qualsiasi)");
                cond.put(k, v);
            }
        String json = json(cond);
        return tx(() -> db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT doc_type FROM ewf_core.process_type WHERE code = ?")) {
                ps.setString(1, e.processType());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.INVALID, "Tipo processo " + e.processType() + " inesistente");
                    if (!e.docType().equals(rs.getString(1)))
                        throw new ServiceException(Code.INVALID, "Il processo " + e.processType() + " e' per il tipo documento "
                                + rs.getString(1) + ", non " + e.docType());
                }
            }
            String sql = e.id() == null ? """
                    INSERT INTO ewf_core.start_rule(doc_type, conditions, priority, process_type_code, active)
                    VALUES (?, ?::jsonb, ?, ?, ?) RETURNING id""" : """
                    UPDATE ewf_core.start_rule SET doc_type = ?, conditions = ?::jsonb, priority = ?, process_type_code = ?,
                           active = ? WHERE id = ? RETURNING id""";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, e.docType());
                ps.setString(2, json);
                ps.setInt(3, e.priority());
                ps.setString(4, e.processType());
                ps.setBoolean(5, e.active());
                if (e.id() != null) ps.setLong(6, e.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ServiceException(Code.NOT_FOUND, "Regola non trovata");
                    return rs.getLong(1);
                }
            }
        }));
    }

    public void deleteRule(long adminId, long id) {
        requireAdmin(adminId);
        tx(() -> db.with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ewf_core.start_rule WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        }));
    }

    /**
     * Avvisi: regole attive che non potrebbero avviare (processo sospeso o senza versione attiva), regole attive
     * identiche (stesse condizioni e priorita': ne vale una sola), tipo documento senza regole attive.
     */
    public List<String> warnings(long adminId, String docType) {
        List<RuleRow> rules = rules(adminId, docType);
        List<String> out = new ArrayList<>();
        List<RuleRow> active = rules.stream().filter(RuleRow::active).toList();
        if (active.isEmpty()) out.add("Nessuna regola attiva: i documenti di questo tipo non avviano nessun processo");
        for (RuleRow r : active)
            if (!r.startable())
                out.add("Regola " + r.id() + ": il processo " + r.processType() + " e' sospeso o non ha una versione attiva"
                        + " (l'avvio finirebbe tra i processi in errore)");
        for (int i = 0; i < active.size(); i++)
            for (int j = i + 1; j < active.size(); j++) {
                RuleRow a = active.get(i), b = active.get(j);
                if (a.priority() == b.priority() && a.conditions().equals(b.conditions()))
                    out.add("Regole " + a.id() + " e " + b.id() + ": stesse condizioni e priorita', vale sempre la "
                            + Math.min(a.id(), b.id()));
            }
        return out;
    }

    /** Quale processo partirebbe per un documento con questi attributi. */
    public SimResult simulate(long adminId, String docType, Map<String, String> context) {
        List<RuleRow> rules = rules(adminId, docType);
        Map<Long, RuleRow> byId = new HashMap<>();
        List<StartRule> active = new ArrayList<>();
        for (RuleRow r : rules) {
            byId.put(r.id(), r);
            if (r.active()) active.add(model(r));
        }
        Map<String, String> ctx = context == null ? Map.of() : context;
        Long winner = StartRule.choose(active, ctx).map(StartRule::id).orElse(null);
        List<SimLine> lines = new ArrayList<>();
        for (RuleRow r : rules) {
            String why = !r.active() ? "non attiva" : model(r).mismatch(ctx);
            boolean win = winner != null && winner == r.id();
            lines.add(new SimLine(r, win, why));
        }
        lines.sort((a, b) -> Boolean.compare(b.winner(), a.winner()));
        if (winner == null)
            return new SimResult("Nessuna regola applicabile: il documento non avvia nessun processo", false, lines);
        RuleRow w = byId.get(winner);
        String note = w.startable() ? "" : " – attenzione: il processo e' sospeso o senza versione attiva";
        return new SimResult("Parte " + w.processType() + " – " + w.processDescription() + " (regola " + w.id() + ")" + note,
                true, lines);
    }

    /** Attributi di un documento vero, letti dal sistema di origine (per il simulatore). */
    public Map<String, String> documentContext(long adminId, String docType, String documentKey) {
        requireAdmin(adminId);
        if (documentKey == null || documentKey.isBlank()) throw new ServiceException(Code.INVALID, "Indicare il documento");
        try {
            return new java.util.TreeMap<>(engine.documentContext(docType, documentKey.trim()));
        } catch (EngineException e) {
            throw ServiceException.from(e);
        } catch (RuntimeException e) {
            throw new ServiceException(Code.BACKEND, "Lettura dal sistema di origine non riuscita: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ interni

    private static StartRule model(RuleRow r) {
        return new StartRule(r.id(), r.docType(), r.conditions(), r.priority(), r.processType());
    }

    private List<RuleRow> rules(Connection c, String docType) throws SQLException {
        Map<Long, Map<String, String>> cond = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT r.id, e.key, e.value FROM ewf_core.start_rule r
                 CROSS JOIN LATERAL jsonb_each_text(r.conditions) e WHERE r.doc_type = ? ORDER BY r.id, e.key""")) {
            ps.setString(1, docType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cond.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getString(3));
            }
        }
        List<RuleRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT r.id, r.doc_type, r.priority, r.process_type_code, t.description, r.active,
                       (NOT t.suspended AND EXISTS (SELECT 1 FROM ewf_core.process_version v
                                                    WHERE v.process_type_code = t.code AND v.status = 'ACTIVE'))
                  FROM ewf_core.start_rule r JOIN ewf_core.process_type t ON t.code = r.process_type_code
                 WHERE r.doc_type = ? ORDER BY r.id""")) {
            ps.setString(1, docType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    out.add(new RuleRow(rs.getLong(1), rs.getString(2), cond.getOrDefault(rs.getLong(1), Map.of()),
                            rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getBoolean(7)));
            }
        }
        return out;
    }

    /** Oggetto JSON piatto di stringhe, con escape. */
    static String json(Map<String, String> m) {
        StringBuilder b = new StringBuilder("{");
        for (var e : m.entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return b.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch);
                }
            }
        }
        return b.append('"').toString();
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        try {
            return db.inTransaction(work);
        } catch (JdbcException e) {
            throw new ServiceException(Code.INVALID, "Errore del database: " + e.getMessage());
        }
    }
}
