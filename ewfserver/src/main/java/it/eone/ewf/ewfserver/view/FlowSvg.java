package it.eone.ewf.ewfserver.view;

import it.eone.ewf.engine.model.AssigneeMode;
import it.eone.ewf.engine.model.HookDef;
import it.eone.ewf.engine.model.HookEvent;
import it.eone.ewf.engine.model.Interaction;
import it.eone.ewf.engine.model.JoinPolicy;
import it.eone.ewf.engine.model.Money;
import it.eone.ewf.engine.model.NotifyMode;
import it.eone.ewf.engine.model.OptionDef;
import it.eone.ewf.engine.model.ProcessVersion;
import it.eone.ewf.engine.model.SpawnMode;
import it.eone.ewf.engine.model.StepDef;
import it.eone.ewf.engine.model.TaskDef;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Diagramma del flusso di una versione di processo, come SVG generato lato server (stessa tecnica del Gantt).
 * <ul>
 * <li>Una riga per numero di step, dall'alto in basso; una colonna per il tronco comune e una per ogni ramo
 * (varianti di step legate a un'opzione di un nodo di scelta).</li>
 * <li>Ogni step e' un riquadro con i suoi task (piu' task = paralleli), chi li esegue, il peso, la soglia di
 * valore. I nodi di scelta elencano le opzioni.</li>
 * <li>Frecce verdi = approvazione / opzione; frecce rosse tratteggiate = rifiuto. "Fine" in basso con l'azione
 * finale; il rifiuto senza destinazione e' scritto nel riquadro.</li>
 * <li>Passando con il mouse su un riquadro compare il dettaglio completo ({@code <title>}).</li>
 * </ul>
 */
public final class FlowSvg {

    public record Result(String svg, int width, int height) {}

    private static final int COL_W = 300;
    private static final int COL_GAP = 120;
    private static final int MARGIN = 24;
    private static final int TOP = 70;           // titolo + avvio
    private static final int ROW_GAP = 54;
    private static final int HEAD_H = 22;
    private static final int TASK_H = 36;
    private static final int OPT_H = 18;
    private static final int PAD = 6;

    private static final String OK = "#2E7D32";
    private static final String KO = "#C62828";
    private static final String TRUNK = "#1F4E79";
    private static final String HANDOFF = "#6A1B9A";
    private static final int MIN_W = 800;
    private static final String[] LANE = {"#6A1B9A", "#00796B", "#E65100", "#283593", "#AD1457", "#558B2F"};

    private FlowSvg() {}

    private static final class Box {
        final StepDef step;
        final int col;
        int x, y, w = COL_W, h;
        int koOut;

        Box(StepDef step, int col) {
            this.step = step;
            this.col = col;
        }

        int cx() { return x + w / 2; }
        int bottom() { return y + h; }
        int right() { return x + w; }
    }

    /**
     * @param errorSteps step da evidenziare in rosso (problemi della validazione); puo' essere vuoto
     */
    public static Result render(ProcessVersion v, String title, Collection<Integer> errorSteps) {
        List<StepDef> steps = v.steps();

        // --- colonne: rami nell'ordine delle opzioni dei nodi di scelta, il tronco in mezzo
        Set<String> branchOptions = new LinkedHashSet<>();
        for (StepDef s : steps)
            for (TaskDef t : s.tasks())
                for (OptionDef o : t.options())
                    if (steps.stream().anyMatch(x -> o.code().equals(x.option()))) branchOptions.add(o.code());
        for (StepDef s : steps) if (s.isVariant()) branchOptions.add(s.option());
        List<String> lanes = new ArrayList<>(branchOptions);
        int left = lanes.size() / 2;
        List<String> cols = new ArrayList<>(lanes.subList(0, left));
        cols.add(null);
        cols.addAll(lanes.subList(left, lanes.size()));
        int trunkCol = left;
        Map<String, String> laneColor = new HashMap<>();
        for (int i = 0; i < lanes.size(); i++) laneColor.put(lanes.get(i), LANE[i % LANE.length]);

        // --- righe
        TreeSet<Integer> rows = new TreeSet<>();
        steps.forEach(s -> rows.add(s.stepNo()));
        Map<String, Box> boxes = new HashMap<>();
        for (StepDef s : steps) boxes.put(key(s.stepNo(), s.option()), new Box(s, cols.indexOf(s.option())));

        int contentW = cols.size() * COL_W + (cols.size() - 1) * COL_GAP + 70;
        int width = Math.max(MIN_W, MARGIN * 2 + contentW);
        int x0 = (width - contentW) / 2;
        int y = TOP;
        Map<Integer, Integer> rowTop = new HashMap<>();
        for (int r : rows) {
            int h = 0;
            for (Box b : boxes.values()) if (b.step.stepNo() == r) h = Math.max(h, boxHeight(b.step));
            rowTop.put(r, y);
            for (Box b : boxes.values())
                if (b.step.stepNo() == r) {
                    b.y = y;
                    b.h = boxHeight(b.step);
                    b.x = x0 + b.col * (COL_W + COL_GAP);
                }
            y += h + ROW_GAP;
        }
        int endY = y;
        int height = endY + 44 + 52;
        int trunkX = x0 + trunkCol * (COL_W + COL_GAP);

        StringBuilder s = new StringBuilder(16_000);
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width).append("\" height=\"").append(height)
         .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
         .append("\" font-family=\"Segoe UI, Arial, sans-serif\" font-size=\"11\">");
        s.append("<defs>");
        marker(s, "aok", OK);
        marker(s, "ako", KO);
        for (String l : lanes) marker(s, "a" + l.replaceAll("[^A-Za-z0-9]", "_"), laneColor.get(l));
        s.append("</defs>");
        s.append("<rect width=\"100%\" height=\"100%\" fill=\"#FFFFFF\"/>");
        s.append("<text x=\"").append(MARGIN).append("\" y=\"22\" font-size=\"14\" font-weight=\"bold\" fill=\"#263238\">")
         .append(esc(title)).append("</text>");

        // intestazioni delle colonne dei rami
        for (int c = 0; c < cols.size(); c++) {
            String o = cols.get(c);
            if (o == null) continue;
            int x = x0 + c * (COL_W + COL_GAP);
            String lab = optionLabel(steps, o);
            String head = lab.toUpperCase(Locale.ROOT).contains(o.toUpperCase(Locale.ROOT)) ? lab : o + " · " + lab;
            s.append("<text x=\"").append(x + COL_W / 2).append("\" y=\"").append(TOP - 10)
             .append("\" text-anchor=\"middle\" font-weight=\"bold\" fill=\"").append(laneColor.get(o)).append("\">")
             .append(esc(head)).append("</text>");
        }

        StringBuilder edges = new StringBuilder();
        StringBuilder labels = new StringBuilder();

        // avvio
        StepDef first = firstStep(v);
        if (first != null) {
            Box b = boxes.get(key(first.stepNo(), first.option()));
            edges.append("<circle cx=\"").append(b.cx()).append("\" cy=\"").append(TOP - 30).append("\" r=\"9\" fill=\"")
                 .append(OK).append("\"><title>Avvio del processo</title></circle>");
            labels.append("<text x=\"").append(b.cx() + 15).append("\" y=\"").append(TOP - 26)
                  .append("\" fill=\"#455A64\">avvio</text>");
            line(edges, OK, false, "aok", b.cx(), TOP - 21, b.cx(), b.y);
        }

        // fine
        int endCx = trunkX + COL_W / 2;
        String endText = v.endHandlerCode().map(h -> "Fine – azione finale " + h + config(v.endHandlerConfig()))
                .orElse("Fine");
        int endW = Math.max(120, (int) (endText.length() * 6.2) + 24);

        Map<Integer, Integer> koSlots = new HashMap<>();
        Map<Integer, Integer> okSlots = new HashMap<>();
        for (Box b : boxes.values()) {
            String laneOpt = b.step.option();
            StepDef st = b.step;
            List<OptionDef> opts = st.tasks().stream().flatMap(t -> t.options().stream()).toList();
            if (opts.isEmpty()) {
                // approvazione
                for (Box t : targets(boxes, st.nextOk(), laneOpt))
                    okEdge(edges, boxes, b, b.cx(), t, OK, "aok", okSlots);
                if (st.nextOk() == null) toEnd(edges, b, b.cx(), endCx, endY, okSlots);
            } else {
                List<OptionDef> forward = opts.stream().filter(o -> !o.reject() && o.spawnMode() != SpawnMode.HANDOFF).toList();
                for (int i = 0; i < forward.size(); i++) {
                    OptionDef o = forward.get(i);
                    int sx = b.x + (i + 1) * b.w / (forward.size() + 1);
                    Box t = boxes.get(key(st.nextOk() == null ? -1 : st.nextOk(), o.code()));
                    if (t == null) t = st.nextOk() == null ? null : boxes.get(key(st.nextOk(), null));
                    String col = laneColor.getOrDefault(o.code(), OK);
                    String mk = laneColor.containsKey(o.code()) ? "a" + o.code().replaceAll("[^A-Za-z0-9]", "_") : "aok";
                    if (t != null) okEdge(edges, boxes, b, sx, t, col, mk, okSlots);
                    else if (st.nextOk() == null) toEnd(edges, b, sx, endCx, endY, okSlots);
                    labels.append("<text x=\"").append(sx + 4).append("\" y=\"").append(b.bottom() + 12)
                          .append("\" font-size=\"10\" font-weight=\"bold\" fill=\"").append(col).append("\">")
                          .append(esc(o.code())).append("</text>");
                }
            }
            // rifiuto: verso next KO (le opzioni di rifiuto usano lo stesso next KO)
            boolean canReject = opts.isEmpty() || opts.stream().anyMatch(OptionDef::reject);
            if (canReject && st.nextKo() != null) {
                for (Box t : targets(boxes, st.nextKo(), laneOpt)) koEdge(edges, b, t, koSlots);
            }
        }
        s.append(edges);

        // riquadri
        for (Box b : boxes.values()) box(s, b, laneColor, errorSteps != null && errorSteps.contains(b.step.stepNo()));

        // fine
        endCx = Math.max(endCx, MARGIN + endW / 2);
        s.append("<rect x=\"").append(endCx - endW / 2).append("\" y=\"").append(endY).append("\" width=\"").append(endW)
         .append("\" height=\"28\" rx=\"14\" fill=\"#E8F5E9\" stroke=\"").append(OK).append("\" stroke-width=\"1.5\">")
         .append("<title>").append(esc(endText)).append("</title></rect>");
        s.append("<text x=\"").append(endCx).append("\" y=\"").append(endY + 18).append("\" text-anchor=\"middle\" fill=\"")
         .append(OK).append("\" font-weight=\"bold\">").append(esc(endText)).append("</text>");
        s.append(labels);

        // legenda
        int ly = height - 26;
        legendLine(s, MARGIN, ly, OK, false, "approvato / opzione scelta");
        legendLine(s, MARGIN + 210, ly, KO, true, "respinto");
        s.append("<text x=\"").append(MARGIN).append("\" y=\"").append(ly + 20).append("\" fill=\"#546E7A\">")
         .append("≥ soglia: lo step serve solo oltre quel valore · ‖ task paralleli · ✉ avviso immediato")
         .append("</text>");
        s.append("</svg>");
        return new Result(s.toString(), width, height);
    }

    // ------------------------------------------------------------------ riquadro

    private static int boxHeight(StepDef s) {
        int opts = s.tasks().stream().mapToInt(t -> t.options().size()).sum();
        return HEAD_H + s.tasks().size() * TASK_H + (opts > 0 ? opts * OPT_H + 4 : 0) + PAD;
    }

    private static void box(StringBuilder s, Box b, Map<String, String> laneColor, boolean error) {
        StepDef st = b.step;
        String head = st.option() == null ? TRUNK : laneColor.getOrDefault(st.option(), TRUNK);
        String border = error ? KO : "#90A4AE";
        s.append("<g><title>").append(esc(tooltip(st))).append("</title>");
        s.append("<rect x=\"").append(b.x).append("\" y=\"").append(b.y).append("\" width=\"").append(b.w)
         .append("\" height=\"").append(b.h).append("\" rx=\"6\" fill=\"#FFFFFF\" stroke=\"").append(border)
         .append("\" stroke-width=\"").append(error ? "2.5" : "1.2").append("\"/>");
        s.append("<path d=\"M").append(b.x).append(',').append(b.y + HEAD_H).append(" v-").append(HEAD_H - 6)
         .append(" a6,6 0 0 1 6,-6 h").append(b.w - 12).append(" a6,6 0 0 1 6,6 v").append(HEAD_H - 6)
         .append(" z\" fill=\"").append(head).append("\"/>");
        String left = "Step " + st.stepNo() + (st.first() ? "  ▶ primo" : "")
                + (st.tasks().size() > 1 ? "  ‖ " + (st.join() == JoinPolicy.ANY ? "uno basta" : "tutti") : "");
        s.append("<text x=\"").append(b.x + 8).append("\" y=\"").append(b.y + 15)
         .append("\" fill=\"#FFFFFF\" font-weight=\"bold\">").append(esc(left)).append("</text>");
        if (st.minAmount() != null)
            s.append("<text x=\"").append(b.right() - 8).append("\" y=\"").append(b.y + 15)
             .append("\" text-anchor=\"end\" fill=\"#FFF59D\" font-weight=\"bold\">≥ ")
             .append(esc(money(st.minAmount()))).append("</text>");
        int y = b.y + HEAD_H;
        for (TaskDef t : st.tasks()) {
            s.append("<text x=\"").append(b.x + 8).append("\" y=\"").append(y + 14).append("\" font-weight=\"bold\" fill=\"#263238\">")
             .append(esc(cut(t.text(), 44))).append("</text>");
            s.append("<text x=\"").append(b.x + 8).append("\" y=\"").append(y + 28).append("\" fill=\"#546E7A\">")
             .append(esc(cut(who(t), 50))).append("</text>");
            y += TASK_H;
            for (OptionDef o : t.options()) {
                String c = o.reject() ? KO : o.spawnMode() == SpawnMode.HANDOFF ? HANDOFF : laneColor.getOrDefault(o.code(), OK);
                s.append("<text x=\"").append(b.x + 14).append("\" y=\"").append(y + 12).append("\" fill=\"").append(c).append("\">")
                 .append("◆ ").append(esc(cut(o.code() + " · " + o.label() + optionTail(o, st), 50))).append("</text>");
                y += OPT_H;
            }
            if (!t.options().isEmpty()) y += 4;
        }
        boolean canReject = st.tasks().stream().allMatch(t -> t.options().isEmpty())
                || st.tasks().stream().anyMatch(t -> t.options().stream().anyMatch(OptionDef::reject));
        boolean optsOnly = st.tasks().stream().anyMatch(TaskDef::isChoice);
        if (canReject && st.nextKo() == null && !optsOnly)
            s.append("<text x=\"").append(b.right() - 8).append("\" y=\"").append(b.bottom() - 6)
             .append("\" text-anchor=\"end\" font-size=\"10\" fill=\"").append(KO).append("\">✗ respinto: fine</text>");
        s.append("</g>");
    }

    private static String optionTail(OptionDef o, StepDef st) {
        if (o.spawnMode() == SpawnMode.HANDOFF) return "  → passa a " + o.startProcess();
        if (o.spawnMode() == SpawnMode.PARALLEL) return "  + avvia " + o.startProcess();
        if (o.reject()) return st.nextKo() == null ? "  ✗ fine" : "  ✗ → " + st.nextKo();
        return "";
    }

    static String who(TaskDef t) {
        StringBuilder w = new StringBuilder();
        switch (t.assigneeMode()) {
            case STARTER -> w.append("Richiedente");
            case ACTOR -> w.append("Chi ha eseguito il passo prima");
            case PREVIOUS -> w.append("Assegnatario del passo prima");
            case HANDLER -> w.append("Handler ").append(nz(t.assigneeHandlerCode()));
            case RULES -> w.append(t.roleCode() == null ? "Regole" : t.roleCode());
        }
        if (t.teamCode() != null) w.append(" · team ").append(t.teamCode());
        else if (t.teamTask()) w.append(" · di team");
        if (t.requiredWeight() > 0) w.append(" · peso ").append(t.requiredWeight());
        if (t.notifyMode() == NotifyMode.IMMEDIATE) w.append(" · ✉");
        if (t.interaction() == Interaction.SAPGUI_ONLY) w.append(" · solo SAP GUI");
        return w.toString();
    }

    private static String tooltip(StepDef st) {
        StringBuilder t = new StringBuilder();
        t.append("Step ").append(st.stepNo());
        if (st.option() != null) t.append(" (ramo ").append(st.option()).append(')');
        if (st.first()) t.append(" – primo step");
        t.append("\nApprovato → ").append(st.nextOk() == null ? "fine" : "step " + st.nextOk());
        t.append("\nRespinto → ").append(st.nextKo() == null ? "fine negativa" : "step " + st.nextKo());
        if (st.minAmount() != null)
            t.append("\nSolo se il valore del documento è ≥ ").append(money(st.minAmount()))
             .append(", altrimenti lo step viene saltato");
        if (st.tasks().size() > 1)
            t.append("\nTask paralleli: ").append(st.join() == JoinPolicy.ANY ? "basta un'approvazione" : "devono approvare tutti");
        for (TaskDef k : st.tasks()) {
            t.append("\n\n• ").append(k.text()).append("\n  ").append(who(k));
            if (k.assigneeMode() == AssigneeMode.RULES && k.roleCode() != null) t.append(" (ruolo)");
            for (HookDef h : k.hooks())
                t.append("\n  ").append(hookName(h.event())).append(": ").append(h.handlerCode())
                 .append(h.label() != null ? " “" + h.label() + "”" : "").append(config(h.config()));
            for (OptionDef o : k.options()) {
                t.append("\n  ◆ ").append(o.code()).append(" ").append(o.label()).append(optionTail(o, st));
                if (o.handlerCode() != null) t.append(" [").append(o.handlerCode()).append(']');
            }
        }
        return t.toString();
    }

    private static String hookName(HookEvent e) {
        return switch (e) {
            case BEFORE_ACTION -> "prima dell'azione";
            case ON_OK -> "su approvazione";
            case ON_KO -> "su rifiuto";
            case ON_ACTIVATE -> "all'arrivo";
            case BUTTON -> "pulsante";
        };
    }

    // ------------------------------------------------------------------ frecce

    private static List<Box> targets(Map<String, Box> boxes, Integer stepNo, String lane) {
        if (stepNo == null) return List.of();
        Box b = lane == null ? null : boxes.get(key(stepNo, lane));
        if (b == null) b = boxes.get(key(stepNo, null));
        if (b != null) return List.of(b);
        // solo varianti: il ramo lo decide una scelta fatta prima
        List<Box> out = new ArrayList<>();
        for (Box x : boxes.values()) if (x.step.stepNo() == stepNo) out.add(x);
        return out;
    }

    private static void okEdge(StringBuilder e, Map<String, Box> boxes, Box from, int sx, Box to, String color, String mk,
                               Map<Integer, Integer> slots) {
        int sy = from.bottom(), tx = to.cx(), ty = to.y;
        if (to.y > from.y) {
            boolean blocked = boxes.values().stream().anyMatch(o -> o != from && o != to && o.col == to.col
                    && o.y > from.y && o.y < to.y);
            if (!blocked && from.col == to.col && sx == tx) {
                line(e, color, false, mk, sx, sy, tx, ty);
                return;
            }
            if (!blocked) {
                int midY = sy + ROW_GAP / 2 - 6 + (slots.merge(from.y, 1, Integer::sum) - 1) % 3 * 5;
                path(e, color, false, mk, sx, sy, sx, midY, tx, midY, tx, ty);
                return;
            }
            int ch = to.x - 14 - (slots.merge(-to.col - 1, 1, Integer::sum) - 1) * 6;
            path(e, color, false, mk, sx, sy, sx, sy + 12, ch, sy + 12, ch, ty - 12, tx, ty - 12, tx, ty);
        } else {
            // verso l'alto (raro per le approvazioni): canale a sinistra
            int ch = Math.min(from.x, to.x) - 14 - (slots.merge(-100 - to.col, 1, Integer::sum) - 1) * 6;
            path(e, color, false, mk, sx, sy, sx, sy + 10, ch, sy + 10, ch, ty - 10, tx, ty - 10, tx, ty);
        }
    }

    private static void toEnd(StringBuilder e, Box from, int sx, int endCx, int endY, Map<Integer, Integer> slots) {
        int sy = from.bottom();
        int midY = endY - 16 - (slots.merge(-999, 1, Integer::sum) - 1) % 4 * 4;
        if (Math.abs(sx - endCx) < 2) line(e, OK, false, "aok", sx, sy, endCx, endY);
        else path(e, OK, false, "aok", sx, sy, sx, midY, endCx, midY, endCx, endY);
    }

    private static void koEdge(StringBuilder e, Box from, Box to, Map<Integer, Integer> slots) {
        int k = slots.merge(from.col, 1, Integer::sum) - 1;
        int ch = from.right() + 14 + k * 7;
        int sy = from.y + HEAD_H + 10;
        int ty = to.y + 8 + (slots.merge(10_000 + to.y * 10 + to.col, 1, Integer::sum) - 1) % 4 * 4;
        int tx = ch >= to.right() ? to.right() : to.x;   // si entra dal lato piu' vicino
        if (tx == to.x && ch > to.x) {
            // bersaglio a destra del canale: si gira intorno dal basso
            path(e, KO, true, "ako", from.right(), sy, ch, sy, ch, ty, to.x - 10, ty, to.x - 10, ty, to.x, ty);
        } else {
            path(e, KO, true, "ako", from.right(), sy, ch, sy, ch, ty, tx, ty);
        }
    }

    private static void line(StringBuilder e, String color, boolean dashed, String mk, int x1, int y1, int x2, int y2) {
        path(e, color, dashed, mk, x1, y1, x2, y2);
    }

    private static void path(StringBuilder e, String color, boolean dashed, String mk, int... p) {
        e.append("<path d=\"M").append(p[0]).append(',').append(p[1]);
        for (int i = 2; i < p.length; i += 2) e.append(" L").append(p[i]).append(',').append(p[i + 1]);
        e.append("\" fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"1.6\"");
        if (dashed) e.append(" stroke-dasharray=\"5,3\"");
        e.append(" marker-end=\"url(#").append(mk).append(")\"/>");
    }

    private static void marker(StringBuilder s, String id, String color) {
        s.append("<marker id=\"").append(id).append("\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">")
         .append("<path d=\"M0,0 L10,5 L0,10 z\" fill=\"").append(color).append("\"/></marker>");
    }

    private static void legendLine(StringBuilder s, int x, int y, String color, boolean dashed, String text) {
        s.append("<line x1=\"").append(x).append("\" y1=\"").append(y).append("\" x2=\"").append(x + 30).append("\" y2=\"")
         .append(y).append("\" stroke=\"").append(color).append("\" stroke-width=\"1.6\"")
         .append(dashed ? " stroke-dasharray=\"5,3\"" : "").append("/>");
        s.append("<text x=\"").append(x + 36).append("\" y=\"").append(y + 4).append("\" fill=\"#546E7A\">").append(esc(text))
         .append("</text>");
    }

    // ------------------------------------------------------------------ utilita'

    private static StepDef firstStep(ProcessVersion v) {
        try {
            return v.firstStep();
        } catch (RuntimeException e) {
            return v.steps().isEmpty() ? null : v.steps().get(0);
        }
    }

    private static String optionLabel(List<StepDef> steps, String code) {
        for (StepDef s : steps)
            for (TaskDef t : s.tasks())
                for (OptionDef o : t.options()) if (o.code().equals(code)) return o.label();
        return "";
    }

    private static String key(int stepNo, String option) {
        return stepNo + "|" + (option == null ? "" : option);
    }

    private static String config(Map<String, String> c) {
        if (c == null || c.isEmpty()) return "";
        StringBuilder b = new StringBuilder(" (");
        c.forEach((k, v) -> b.append(b.length() > 2 ? ", " : "").append(k).append('=').append(v));
        return b.append(')').toString();
    }

    static String money(Money m) {
        DecimalFormat f = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ITALY));
        BigDecimal a = m.amount();
        return f.format(a) + " " + m.currency();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
