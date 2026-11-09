package it.eone.ewf.sap.migration;

import it.eone.ewf.engine.jdbc.JdbcDefinitionWriter;
import it.eone.ewf.engine.model.HookDef;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.engine.model.StepDef;
import it.eone.ewf.engine.model.TaskDef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Rapporto di migrazione in una pagina HTML autonoma, piu' un CSV delle osservazioni. */
public final class HtmlReport {

    public static void write(Path html, String source, MigrationPlan plan, MigrationWriter.Result result) throws IOException {
        StringBuilder b = new StringBuilder();
        long errors = plan.issues.stream().filter(i -> i.severity() == Issue.Severity.ERROR).count()
                + result.versions().stream().flatMap(v -> v.problems().stream()).filter(p -> p.severity().equals("ERROR")).count();
        long warnings = plan.issues.stream().filter(i -> i.severity() == Issue.Severity.WARNING).count()
                + result.versions().stream().flatMap(v -> v.problems().stream()).filter(p -> p.severity().equals("WARNING")).count();

        b.append("""
                <!DOCTYPE html><html lang="it"><head><meta charset="utf-8"><title>Migrazione eWF</title><style>
                body{font:14px/1.45 system-ui,Segoe UI,sans-serif;margin:2rem auto;max-width:1100px;color:#1d2433;padding:0 1rem}
                h1{font-size:1.5rem;margin-bottom:.2rem}h2{font-size:1.15rem;margin-top:2rem;border-bottom:1px solid #dde2ea;padding-bottom:.3rem}
                .sub{color:#5b6475}.cards{display:flex;gap:1rem;flex-wrap:wrap;margin:1.2rem 0}
                .card{border:1px solid #dde2ea;border-radius:8px;padding:.7rem 1rem;min-width:140px}
                .card b{display:block;font-size:1.5rem}.err b{color:#b42318}.warn b{color:#b54708}.ok b{color:#067647}
                table{border-collapse:collapse;width:100%;margin:.5rem 0}th,td{text-align:left;padding:.35rem .5rem;border-bottom:1px solid #eef1f5;vertical-align:top}
                th{background:#f6f8fa;font-weight:600}.tag{display:inline-block;padding:0 .45rem;border-radius:4px;font-size:.8rem;font-weight:600}
                .ERROR{background:#fee4e2;color:#b42318}.WARNING{background:#fef0c7;color:#b54708}.INFO{background:#e8f0fe;color:#1d4ed8}
                .ACTIVE{background:#dcfae6;color:#067647}.DRAFT{background:#fee4e2;color:#b42318}code{font-size:.85rem}
                details{margin:.3rem 0}summary{cursor:pointer}
                </style></head><body>
                """);
        b.append("<h1>Migrazione eWF – ").append(result.dryRun() ? "prova generale" : "applicata").append("</h1>");
        b.append("<div class=sub>Sorgente: <code>").append(esc(source)).append("</code> · ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
                .append(result.dryRun() ? " · <b>nessun dato scritto</b> (rollback finale)" : " · dati scritti nel DB")
                .append("</div>");

        long active = result.versions().stream().filter(MigrationWriter.VersionOutcome::active).count();
        b.append("<div class=cards>")
                .append(card("", plan.types.size(), "processi"))
                .append(card("ok", active, "attivabili"))
                .append(card(errors > 0 ? "err" : "ok", errors, "errori"))
                .append(card(warnings > 0 ? "warn" : "ok", warnings, "avvisi"))
                .append(card("", plan.users.size(), "utenti"))
                .append(card("", plan.rules.size(), "regole"))
                .append("</div>");

        b.append("<h2>Letto da SAP / scritto</h2><table><tr><th>Tabella /EWF/</th><th>Righe lette</th></tr>");
        plan.readCounts.forEach((k, v) -> b.append("<tr><td>").append(esc(k)).append("</td><td>").append(v).append("</td></tr>"));
        b.append("</table><table><tr><th>Oggetto nel nuovo modello</th><th>").append(result.dryRun() ? "Verrebbero scritti" : "Scritti").append("</th></tr>");
        result.written().forEach((k, v) -> b.append("<tr><td>").append(esc(k)).append("</td><td>").append(v).append("</td></tr>"));
        b.append("</table>");

        b.append("<h2>Processi</h2><table><tr><th>Processo</th><th>Stato</th><th>Step</th><th>Fine processo</th><th>Validazione</th></tr>");
        Map<String, ProcessVersion> byType = plan.versions.stream()
                .collect(Collectors.toMap(ProcessVersion::processTypeCode, v -> v, (a, x) -> a, TreeMap::new));
        for (MigrationWriter.VersionOutcome o : result.versions()) {
            ProcessVersion v = byType.get(o.processType());
            String end = v.endHandlerCode().orElse("–") + (v.endHandlerConfig().isEmpty() ? "" : " " + v.endHandlerConfig());
            b.append("<tr><td><b>").append(esc(o.processType())).append("</b></td><td><span class='tag ")
                    .append(o.active() ? "ACTIVE'>ATTIVO" : "DRAFT'>BOZZA").append("</span></td><td>")
                    .append(v.steps().size()).append("</td><td><code>").append(esc(end)).append("</code></td><td>");
            if (o.problems().isEmpty()) b.append("ok");
            for (JdbcDefinitionWriter.Problem p : o.problems())
                b.append("<span class='tag ").append(p.severity()).append("'>").append(p.severity()).append("</span> step ")
                        .append(p.stepNo() == null ? "–" : p.stepNo()).append(": ").append(esc(p.problem())).append("<br>");
            b.append("</td></tr>");
            b.append("<tr><td></td><td colspan=4><details><summary>dettaglio step</summary>").append(steps(v)).append("</details></td></tr>");
        }
        b.append("</table>");

        b.append("<h2>Osservazioni</h2><table><tr><th>Livello</th><th>Area</th><th>Oggetto</th><th>Messaggio</th></tr>");
        plan.issues.stream()
                .sorted(Comparator.comparing(Issue::severity).thenComparing(Issue::area).thenComparing(Issue::object))
                .forEach(i -> b.append("<tr><td><span class='tag ").append(i.severity()).append("'>").append(i.severity())
                        .append("</span></td><td>").append(esc(i.area())).append("</td><td>").append(esc(i.object()))
                        .append("</td><td>").append(esc(i.message())).append("</td></tr>"));
        b.append("</table>");

        b.append("<h2>Handler (FM ABAP)</h2><table><tr><th>FM</th><th>Contratto</th><th>Usata in</th></tr>");
        plan.handlers.forEach((code, contract) -> b.append("<tr><td><code>").append(esc(code)).append("</code></td><td>")
                .append(contract).append("</td><td>").append(esc(String.join(", ", plan.handlerUsage.get(code))))
                .append("</td></tr>"));
        b.append("</table></body></html>");
        Files.writeString(html, b, StandardCharsets.UTF_8);

        // CSV delle osservazioni (separatore ';', UTF-8 con BOM per Excel)
        StringBuilder csv = new StringBuilder("﻿livello;area;oggetto;messaggio\n");
        for (Issue i : plan.issues)
            csv.append(i.severity()).append(';').append(c(i.area())).append(';').append(c(i.object())).append(';')
                    .append(c(i.message())).append('\n');
        for (MigrationWriter.VersionOutcome o : result.versions())
            for (JdbcDefinitionWriter.Problem p : o.problems())
                csv.append(p.severity()).append(";Validazione;").append(c(o.processType() + " step " + p.stepNo()))
                        .append(';').append(c(p.problem())).append('\n');
        Path csvPath = html.resolveSibling(html.getFileName().toString().replaceAll("\\.html?$", "") + ".csv");
        Files.writeString(csvPath, csv, StandardCharsets.UTF_8);
    }

    private static String steps(ProcessVersion v) {
        StringBuilder s = new StringBuilder("<table><tr><th>Step</th><th>OK →</th><th>KO →</th><th>Soglia</th><th>Task</th></tr>");
        for (StepDef st : v.steps()) {
            s.append("<tr><td>").append(st.stepNo()).append(st.first() ? " (primo)" : "").append("</td><td>")
                    .append(st.nextOk() == null ? "fine" : st.nextOk()).append("</td><td>")
                    .append(st.nextKo() == null ? "fine" : st.nextKo()).append("</td><td>")
                    .append(st.minAmount() == null ? "" : st.minAmount().amount() + " " + st.minAmount().currency())
                    .append("</td><td>");
            for (TaskDef t : st.tasks()) {
                s.append(esc(t.text())).append(" · ruolo <code>").append(esc(String.valueOf(t.roleCode())))
                        .append("</code> · peso ").append(t.requiredWeight()).append(" · ").append(t.assigneeMode());
                if (t.assigneeHandlerCode() != null) s.append(" <code>").append(esc(t.assigneeHandlerCode())).append("</code>");
                for (HookDef h : t.hooks()) s.append("<br>&nbsp;&nbsp;").append(h.event()).append(" <code>").append(esc(h.handlerCode())).append("</code>");
                s.append("<br>");
            }
            s.append("</td></tr>");
        }
        return s.append("</table>").toString();
    }

    private static String card(String cls, long n, String label) {
        return "<div class='card " + cls + "'><b>" + n + "</b>" + label + "</div>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    private static String c(String s) {
        String v = s == null ? "" : s.replace("\"", "\"\"");
        return v.contains(";") || v.contains("\"") ? "\"" + v + "\"" : v;
    }
}
