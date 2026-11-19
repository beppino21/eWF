package it.eone.ewf.ewfserver.view;

import it.eone.ewf.service.Views.TimelineBar;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Disegna il Gantt di un processo come SVG (mostrato con t:svgviewer).
 *
 * Una riga per persona (o team) nell'ordine in cui entra nel processo; ogni attivita' e' una barra dall'arrivo
 * all'esecuzione, col colore dell'esito, il nome dell'attivita' e la durata scritti accanto.
 *
 * Scala del tempo COMPRESSA: tra un evento e il successivo lo spazio cresce col logaritmo della durata, cosi'
 * un'approvazione di due minuti resta leggibile anche in un processo durato settimane. Le date degli eventi sono
 * scritte sotto l'asse; i tratti accorciati sono segnati con due barrette.
 */
public final class GanttSvg {

    private static final int LEFT = 190;          // colonna dei nomi
    private static final int RIGHT_PAD = 170;     // spazio per l'etichetta dell'ultima barra
    private static final int TOP = 34;
    private static final int ROW_H = 34;
    private static final int BAR_H = 16;
    private static final int AXIS_H = 74;
    private static final int LEGEND_H = 30;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private GanttSvg() { }

    public record Result(String svg, int width, int height) {}

    public static Result render(List<TimelineBar> bars, Instant now, ZoneId zone, int width) {
        if (bars == null || bars.isEmpty()) return empty(width);
        // ---- eventi (inizi e fini) ordinati: la scala si costruisce su questi
        TreeSet<Instant> events = new TreeSet<>();
        for (TimelineBar b : bars) {
            if (b.from() != null) events.add(b.from());
            events.add(b.to() == null ? now : b.to());
        }
        List<Instant> ev = new ArrayList<>(events);
        int plotW = Math.max(width - LEFT - RIGHT_PAD, 200);
        double[] x = new double[ev.size()];
        double total = 0;
        double[] gap = new double[ev.size()];
        for (int i = 1; i < ev.size(); i++) {
            double minutes = Math.max(Duration.between(ev.get(i - 1), ev.get(i)).toSeconds() / 60.0, 0);
            gap[i] = minutes <= 0 ? 0 : 1 + Math.log1p(minutes);        // 1 min ~ 1.7, 1 h ~ 5.1, 1 g ~ 8.3, 1 sett ~ 10.2
            total += gap[i];
        }
        double unit = total == 0 ? 0 : plotW / total;
        for (int i = 1; i < ev.size(); i++) x[i] = x[i - 1] + gap[i] * unit;

        Map<String, List<TimelineBar>> rows = new LinkedHashMap<>();
        for (TimelineBar b : bars) rows.computeIfAbsent(b.who(), k -> new ArrayList<>()).add(b);

        int height = TOP + rows.size() * ROW_H + AXIS_H + LEGEND_H;
        StringBuilder s = new StringBuilder(8192);
        s.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width).append("\" height=\"").append(height)
         .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
         .append("\" font-family=\"Segoe UI, Arial, sans-serif\" font-size=\"12\">");
        s.append("<rect x=\"0\" y=\"0\" width=\"").append(width).append("\" height=\"").append(height).append("\" fill=\"#FFFFFF\"/>");

        // ---- righe alternate e nomi
        int r = 0;
        for (String who : rows.keySet()) {
            int y = TOP + r * ROW_H;
            if (r % 2 == 0)
                s.append("<rect x=\"0\" y=\"").append(y).append("\" width=\"").append(width).append("\" height=\"").append(ROW_H)
                 .append("\" fill=\"#F5F7FA\"/>");
            s.append("<text x=\"10\" y=\"").append(y + ROW_H / 2 + 4).append("\" font-weight=\"bold\" fill=\"#333333\">")
             .append(esc(cut(who, 26))).append("</text>");
            r++;
        }
        int plotBottom = TOP + rows.size() * ROW_H;

        // ---- griglia verticale sugli eventi (senza duplicare quelli troppo vicini)
        double lastLine = -100;
        for (int i = 0; i < ev.size(); i++) {
            double px = LEFT + x[i];
            if (px - lastLine < 3) continue;
            s.append("<line x1=\"").append(f(px)).append("\" y1=\"").append(TOP - 6).append("\" x2=\"").append(f(px))
             .append("\" y2=\"").append(plotBottom).append("\" stroke=\"#E0E0E0\" stroke-width=\"1\"/>");
            lastLine = px;
        }

        // ---- barre: l'etichetta (attivita' e durata) va dove c'e' spazio fino alla barra successiva della riga;
        //      se non ci sta si scrive solo la durata, se non ci sta nemmeno quella resta il suggerimento al passaggio
        r = 0;
        StringBuilder diamonds = new StringBuilder();
        for (Map.Entry<String, List<TimelineBar>> row : rows.entrySet()) {
            int yMid = TOP + r * ROW_H + ROW_H / 2;
            List<TimelineBar> rb = new ArrayList<>(row.getValue());
            rb.removeIf(b -> "START".equals(b.status()));
            rb.sort((a, b) -> a.from().compareTo(b.from()));
            for (TimelineBar b : row.getValue()) {
                if ("START".equals(b.status())) {
                    double x1 = LEFT + x[ev.indexOf(b.from())];
                    diamonds.append("<path d=\"M").append(f(x1)).append(' ').append(yMid - 9).append(" l7 9 l-7 9 l-7 -9 z\" fill=\"")
                            .append(color(b.status())).append("\" stroke=\"#455A64\" stroke-width=\"1.2\"><title>Avvio del processo</title></path>");
                }
            }
            for (int k = 0; k < rb.size(); k++) {
                TimelineBar b = rb.get(k);
                Instant to = b.to() == null ? now : b.to();
                double x1 = LEFT + x[ev.indexOf(b.from())];
                double x2 = LEFT + x[ev.indexOf(to)];
                double w = Math.max(x2 - x1, 6);
                boolean open = b.to() == null;
                s.append("<rect x=\"").append(f(x1)).append("\" y=\"").append(yMid - BAR_H / 2).append("\" width=\"").append(f(w))
                 .append("\" height=\"").append(BAR_H).append("\" rx=\"3\" fill=\"").append(color(b.status())).append('"');
                if (open) s.append(" stroke=\"#F9A825\" stroke-dasharray=\"4 2\"");
                s.append("><title>").append(esc(tooltip(b, zone))).append("</title></rect>");
                double limit = k + 1 < rb.size() ? LEFT + x[ev.indexOf(rb.get(k + 1).from())] - 4 : width - 4;
                String dur = duration(b.from(), to) + (open ? " (in corso)" : "");
                String full = cut(b.taskText(), 32) + " · " + dur;
                if (w >= textWidth(full) + 10) {
                    s.append(text(x1 + 5, yMid + 4, full, 12, "#212121"));
                } else if (x1 + w + 4 + textWidth(full) <= limit) {
                    s.append(text(x1 + w + 4, yMid + 4, full, 11, "#424242"));
                } else if (w >= textWidth(dur) + 10) {
                    s.append(text(x1 + 5, yMid + 4, dur, 11, "#212121"));
                } else if (x1 + w + 4 + textWidth(dur) <= limit) {
                    s.append(text(x1 + w + 4, yMid + 4, dur, 11, "#424242"));
                }
            }
            r++;
        }
        s.append(diamonds);

        // ---- asse: data/ora degli eventi (diradati), e segni di compressione sui tratti lunghi
        int axisY = plotBottom + 6;
        s.append("<line x1=\"").append(LEFT).append("\" y1=\"").append(axisY).append("\" x2=\"").append(f(LEFT + plotW))
         .append("\" y2=\"").append(axisY).append("\" stroke=\"#9E9E9E\"/>");
        double lastLabel = -1000;
        String lastDay = "";
        String lastTime = "";
        for (int i = 0; i < ev.size(); i++) {
            double px = LEFT + x[i];
            if (i > 0 && gap[i] > 7.5) {                                   // tratto di oltre ~1 giorno: compresso
                double mid = LEFT + (x[i - 1] + x[i]) / 2;
                s.append("<path d=\"M").append(f(mid - 5)).append(' ').append(axisY + 5).append(" l4 -10 M")
                 .append(f(mid + 1)).append(' ').append(axisY + 5).append(" l4 -10\" stroke=\"#757575\" stroke-width=\"1.5\"/>");
                s.append("<text x=\"").append(f(mid)).append("\" y=\"").append(axisY + 18)
                 .append("\" text-anchor=\"middle\" font-size=\"10\" fill=\"#757575\">")
                 .append(esc(duration(ev.get(i - 1), ev.get(i)))).append("</text>");
            }
            if (px - lastLabel < 46) continue;
            String day = DAY.format(ev.get(i).atZone(zone));
            String time = TIME.format(ev.get(i).atZone(zone));
            if (time.equals(lastTime) && day.equals(lastDay)) continue;      // stesso minuto: una sola etichetta
            lastTime = time;
            s.append("<line x1=\"").append(f(px)).append("\" y1=\"").append(axisY).append("\" x2=\"").append(f(px))
             .append("\" y2=\"").append(axisY + 4).append("\" stroke=\"#9E9E9E\"/>");
            s.append("<text x=\"").append(f(px)).append("\" y=\"").append(axisY + 34).append("\" text-anchor=\"middle\" font-size=\"10\" fill=\"#424242\">")
             .append(esc(TIME.format(ev.get(i).atZone(zone)))).append("</text>");
            if (!day.equals(lastDay))
                s.append("<text x=\"").append(f(px)).append("\" y=\"").append(axisY + 47).append("\" text-anchor=\"middle\" font-size=\"10\" font-weight=\"bold\" fill=\"#424242\">")
                 .append(day).append("</text>");
            lastDay = day;
            lastLabel = px;
        }
        s.append("<text x=\"10\" y=\"").append(axisY + 34).append("\" font-size=\"10\" fill=\"#757575\">ora</text>");
        s.append("<text x=\"10\" y=\"").append(axisY + 47).append("\" font-size=\"10\" fill=\"#757575\">giorno</text>");
        s.append("<text x=\"10\" y=\"").append(axisY + 62).append("\" font-size=\"10\" fill=\"#9E9E9E\">")
         .append("Scala compressa: le attese lunghe sono accorciate (//) per lasciare leggibili quelle brevi.</text>");

        // ---- legenda
        int ly = height - 12;
        int lx = 10;
        String[][] legend = {{"Avvio", "#B0BEC5"}, {"Approvato", "#81C784"}, {"Respinto", "#E57373"}, {"Spostato", "#90CAF9"},
                             {"In corso", "#FFE082"}, {"Errore", "#FF8A65"}, {"Annullato / non necessario", "#E0E0E0"}};
        for (String[] l : legend) {
            s.append("<rect x=\"").append(lx).append("\" y=\"").append(ly - 10).append("\" width=\"14\" height=\"12\" rx=\"2\" fill=\"")
             .append(l[1]).append("\"/>");
            s.append("<text x=\"").append(lx + 19).append("\" y=\"").append(ly).append("\" font-size=\"11\" fill=\"#424242\">")
             .append(l[0]).append("</text>");
            lx += 19 + l[0].length() * 6 + 18;
        }
        s.append("</svg>");
        return new Result(s.toString(), width, height);
    }

    private static Result empty(int width) {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"60\"><text x=\"10\" y=\"30\""
                + " font-family=\"Arial\" font-size=\"12\" fill=\"#757575\">Nessuna attivita' da mostrare.</text></svg>";
        return new Result(svg, width, 60);
    }

    static String color(String status) {
        return switch (status == null ? "" : status) {
            case "START" -> "#B0BEC5";
            case "OK" -> "#81C784";
            case "KO" -> "#E57373";
            case "MOVED" -> "#90CAF9";
            case "OPEN", "EXECUTING" -> "#FFE082";
            case "ERROR" -> "#FF8A65";
            default -> "#E0E0E0";
        };
    }

    /** "3 min", "2 h 10 min", "4 g 6 h". */
    static String duration(Instant a, Instant b) {
        long m = Math.max(Duration.between(a, b).toMinutes(), 0);
        if (m < 1) return "< 1 min";
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 24) return h + " h" + (m % 60 == 0 ? "" : " " + (m % 60) + " min");
        long d = h / 24;
        return d + " g" + (h % 24 == 0 ? "" : " " + (h % 24) + " h");
    }

    private static String tooltip(TimelineBar b, ZoneId zone) {
        DateTimeFormatter dt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        StringBuilder t = new StringBuilder(b.taskText());
        if (b.option() != null) t.append(" - ").append(b.option());
        t.append("\nArrivata: ").append(dt.format(b.from().atZone(zone)));
        t.append(b.to() == null ? "\nAncora aperta" : "\nEseguita: " + dt.format(b.to().atZone(zone)));
        if (b.forWhom() != null) t.append("\nPer conto di ").append(b.forWhom());
        if (b.note() != null && !b.note().isBlank()) t.append("\nNota: ").append(b.note());
        return t.toString();
    }

    /** Larghezza stimata del testo (Arial 11-12 px): basta per decidere se un'etichetta ci sta. */
    private static double textWidth(String s) { return s.length() * 6.2; }

    private static String text(double x, int y, String s, int size, String color) {
        return "<text x=\"" + f(x) + "\" y=\"" + y + "\" font-size=\"" + size + "\" fill=\"" + color + "\">" + esc(s) + "</text>";
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String f(double v) { return String.valueOf(Math.round(v * 10) / 10.0); }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
