package it.eone.connect.scheduler;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pianificazione di un job, scritta in chiaro e salvata nel DB:
 *   every 30s | every 5m | every 2h
 *   daily 06:00                     tutti i giorni
 *   daily 06:00,18:30 mon-fri       piu' orari, giorni lavorativi
 *   daily 07:15 mon,wed,fri
 * Gli orari "daily" sono nel fuso del job: il cambio d'ora legale non li sposta.
 */
public sealed interface Schedule permits Schedule.Every, Schedule.Daily {

    /** Prossima esecuzione strettamente successiva a "after". */
    Instant next(Instant after);

    String text();

    record Every(Duration period) implements Schedule {
        public Every {
            if (period.isNegative() || period.isZero()) throw new IllegalArgumentException("Intervallo non valido");
        }
        @Override public Instant next(Instant after) { return after.plus(period); }
        @Override public String text() {
            long s = period.toSeconds();
            if (s % 3600 == 0) return "every " + s / 3600 + "h";
            if (s % 60 == 0) return "every " + s / 60 + "m";
            return "every " + s + "s";
        }
    }

    record Daily(List<LocalTime> times, Set<DayOfWeek> days, ZoneId zone) implements Schedule {
        public Daily {
            if (times.isEmpty()) throw new IllegalArgumentException("Almeno un orario");
            times = times.stream().sorted().distinct().toList();
            days = days.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : EnumSet.copyOf(days);
        }

        @Override public Instant next(Instant after) {
            LocalDate d = after.atZone(zone).toLocalDate();
            for (int i = 0; i < 8; i++, d = d.plusDays(1)) {
                if (!days.contains(d.getDayOfWeek())) continue;
                for (LocalTime t : times) {
                    // orario inesistente (passaggio all'ora legale): ZonedDateTime lo sposta in avanti
                    Instant candidate = ZonedDateTime.of(d, t, zone).toInstant();
                    if (candidate.isAfter(after)) return candidate;
                }
            }
            throw new IllegalStateException("Nessuna esecuzione trovata in una settimana");
        }

        @Override public String text() {
            String t = times.stream().map(x -> String.format("%02d:%02d", x.getHour(), x.getMinute()))
                    .collect(Collectors.joining(","));
            if (days.size() == 7) return "daily " + t;
            return "daily " + t + " " + days.stream().sorted().map(Schedule::dayName).collect(Collectors.joining(","));
        }
    }

    static Schedule parse(String text, ZoneId zone) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String[] p = s.split(" ");
        try {
            if (p[0].equals("every") && p.length == 2) {
                String v = p[1];
                long n = Long.parseLong(v.substring(0, v.length() - 1));
                return new Every(switch (v.charAt(v.length() - 1)) {
                    case 's' -> Duration.ofSeconds(n);
                    case 'm' -> Duration.ofMinutes(n);
                    case 'h' -> Duration.ofHours(n);
                    default -> throw new IllegalArgumentException("unita' " + v);
                });
            }
            if (p[0].equals("daily") && (p.length == 2 || p.length == 3)) {
                List<LocalTime> times = new ArrayList<>();
                for (String t : p[1].split(",")) times.add(LocalTime.parse(t.length() == 4 ? "0" + t : t));
                Set<DayOfWeek> days = p.length == 3 ? days(p[2]) : EnumSet.noneOf(DayOfWeek.class);
                return new Daily(times, days, zone);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Pianificazione non valida: '" + text + "' (" + e.getMessage() + ")");
        }
        throw new IllegalArgumentException("Pianificazione non valida: '" + text
                + "' (attese: every 30s|5m|2h, daily HH:MM[,HH:MM] [mon-fri|mon,wed])");
    }

    private static Set<DayOfWeek> days(String spec) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        for (String part : spec.split(",")) {
            if (part.contains("-")) {
                DayOfWeek a = day(part.split("-")[0]), b = day(part.split("-")[1]);
                for (DayOfWeek d = a; ; d = d.plus(1)) {
                    out.add(d);
                    if (d == b) break;
                }
            } else out.add(day(part));
        }
        return out;
    }

    /** I campi di un'interfaccia sono sempre pubblici: i nomi dei giorni restano dietro un metodo privato. */
    private static List<String> names() { return List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun"); }

    private static DayOfWeek day(String s) {
        int i = names().indexOf(s);
        if (i < 0) throw new IllegalArgumentException("giorno " + s);
        return DayOfWeek.of(i + 1);
    }

    private static String dayName(DayOfWeek d) { return names().get(d.getValue() - 1); }
}
