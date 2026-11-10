package it.eone.connect.scheduler;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private static Instant rome(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ROME).toInstant();
    }

    @Test
    void intervalli() {
        Schedule s = Schedule.parse("every 30s", ROME);
        assertEquals("every 30s", s.text());
        Instant t = Instant.parse("2026-11-10T10:00:00Z");
        assertEquals(Instant.parse("2026-11-10T10:00:30Z"), s.next(t));
        assertEquals("every 5m", Schedule.parse("Every  300s", ROME).text(), "normalizzato");
        assertEquals("every 2h", Schedule.parse("every 2h", ROME).text());
    }

    @Test
    void giornalieroStessoGiornoOGiornoDopo() {
        Schedule s = Schedule.parse("daily 06:00", ROME);
        assertEquals(rome("2026-11-10T06:00"), s.next(rome("2026-11-10T05:59")));
        assertEquals(rome("2026-11-11T06:00"), s.next(rome("2026-11-10T06:00")), "strettamente dopo");
    }

    @Test
    void piuOrariEGiorniLavorativi() {
        Schedule s = Schedule.parse("daily 18:30,06:00 mon-fri", ROME);
        assertEquals("daily 06:00,18:30 mon,tue,wed,thu,fri", s.text());
        // venerdi' 13/11/2026 dopo le 18:30 -> lunedi' 16/11 alle 06:00
        assertEquals(rome("2026-11-16T06:00"), s.next(rome("2026-11-13T19:00")));
        assertEquals(rome("2026-11-13T18:30"), s.next(rome("2026-11-13T07:00")));
        assertEquals(rome("2026-11-11T06:00"), Schedule.parse("daily 6:00 mon,wed", ROME).next(rome("2026-11-09T07:00")));
    }

    @Test
    void ilCambioDOraNonSpostaLOrario() {
        Schedule s = Schedule.parse("daily 06:00", ROME);
        // 25/10/2026: fine dell'ora legale, la giornata dura 25 ore
        Instant sat = rome("2026-10-24T06:00");
        Instant sun = s.next(sat);
        assertEquals(rome("2026-10-25T06:00"), sun);
        assertEquals(Duration.ofHours(25), Duration.between(sat, sun), "sempre alle 06:00 locali");
        assertEquals(6, sun.atZone(ROME).getHour());
    }

    @Test
    void orarioInesistenteAlPassaggioAllOraLegale() {
        // 29/03/2026: alle 02:00 si passa alle 03:00, le 02:30 non esistono
        Instant n = Schedule.parse("daily 02:30", ROME).next(rome("2026-03-28T12:00"));
        assertEquals(LocalTime.of(3, 30), n.atZone(ROME).toLocalTime(), "spostato in avanti, non perso");
        assertEquals(LocalDate.of(2026, 3, 29), n.atZone(ROME).toLocalDate());
    }

    @Test
    void pianificazioniNonValide() {
        for (String bad : new String[]{"", "every", "every 0s", "every 5x", "daily", "daily 25:00", "daily 06:00 xyz", "weekly"})
            assertThrows(IllegalArgumentException.class, () -> Schedule.parse(bad, ROME), bad);
    }
}
