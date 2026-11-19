package it.eone.ewf.ewfserver.view;

import it.eone.connect.db.Db;
import it.eone.connect.mail.MailMessage;
import it.eone.connect.mail.MailOutbox;
import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.config.AppConfig;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.SubstitutionService;
import it.eone.ewf.service.SubstitutionService.Candidate;
import it.eone.ewf.service.SubstitutionService.Contact;
import it.eone.ewf.service.SubstitutionService.Substitution;
import it.eone.ewf.service.Views.Reason;
import it.eone.ewf.service.Views.WorkItem;
import it.eone.ewf.service.Views.WorklistFilter;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Auto-sostituzione, come nel ticketing: l'utente indica UN sostituto per un periodo. Nel periodo il sostituto
 * vede e firma le sue attivita' (anche quelle gia' in attesa); il sostituto riceve una mail di avviso.
 */
@CCGenClass(expressionBase = "#{d.AutoSubstitutionUI}")
public class AutoSubstitutionUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private IBackToMenu m_back;
    private final ValidValuesBinding m_substituteVVB = new ValidValuesBinding();
    private String m_substitute;
    private LocalDate m_from;
    private LocalDate m_to;
    private Substitution m_existing;
    private List<Substitution> m_covering = List.of();
    private boolean m_canManage;

    public AutoSubstitutionUI() {
        SessionGuard.requireUser("/AutoSubstitution.xml");
    }

    @Override public String getPageName() { return "/AutoSubstitution.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.AutoSubstitutionUI}"; }

    public void prepare(IBackToMenu back) {
        m_back = back;
        load();
    }

    private static SubstitutionService svc() { return EwfApp.get().substitutions(); }
    private static long me() { return EwfSession.instance().userId(); }

    private void load() {
        try {
            m_canManage = svc().canManage(me());
            List<Substitution> mine = svc().mine(me());
            m_existing = mine.isEmpty() ? null : mine.get(0);
            m_covering = svc().asSubstitute(me());
            m_substituteVVB.clear();
            for (Candidate c : svc().candidates(me())) m_substituteVVB.addValidValue(String.valueOf(c.id()), c.name());
            if (m_existing != null) {
                m_substitute = String.valueOf(m_existing.substituteId());
                m_from = m_existing.from();
                m_to = m_existing.to();
            } else {
                m_substitute = null;
                m_from = null;
                m_to = null;
            }
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ azioni

    public void onBackToMenu(ActionEvent ae) {
        if (m_back != null) m_back.backToMenu();
    }

    public void onSave(ActionEvent ae) {
        if (m_substitute == null || m_from == null || m_to == null) {
            Statusbar.outputError("Indicare il sostituto e il periodo (dal / al)");
            return;
        }
        try {
            Substitution s = svc().setMine(me(), Long.parseLong(m_substitute), m_from, m_to);
            boolean mailed = notifySubstitute(s);
            load();
            Statusbar.outputSuccess("Sostituzione salvata: " + s.substituteName() + " dal " + DAY.format(s.from())
                    + " al " + DAY.format(s.to()) + (mailed ? " (avvisato via mail)" : ""));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onRemove(ActionEvent ae) {
        if (m_existing == null) return;
        YESNOPopup.createInstance("Auto-sostituzione", "Rimuovere la sostituzione di " + m_existing.substituteName() + "?",
                new YESNOPopup.IYesNoListener() {
                    @Override public void reactOnYes() {
                        try {
                            svc().clearMine(me());
                            load();
                            Statusbar.outputSuccess("Sostituzione rimossa");
                        } catch (ServiceException e) {
                            Statusbar.outputError(e.getMessage());
                        }
                    }
                    @Override public void reactOnNo() { }
                });
    }

    /**
     * Mail al sostituto (in coda, la invia il job della posta): chi sostituisce, il periodo, quante attivita'
     * sono gia' in attesa. Se il sostituto non ha e-mail non si manda nulla; un errore non blocca il salvataggio.
     */
    private boolean notifySubstitute(Substitution s) {
        try {
            Optional<Contact> to = svc().contact(s.substituteId());
            if (to.isEmpty() || to.get().email() == null || to.get().email().isBlank()) return false;
            List<WorkItem> waiting = EwfApp.get().service().worklist(me(), WorklistFilter.all()).items().stream()
                    .filter(w -> w.reason() == Reason.OWN).toList();
            StringBuilder body = new StringBuilder();
            body.append("Ciao ").append(to.get().name()).append(",\n\n")
                .append(s.userName()).append(" ti ha indicato come sostituto in eWF dal ")
                .append(DAY.format(s.from())).append(" al ").append(DAY.format(s.to())).append(".\n")
                .append("In questo periodo vedrai e potrai firmare le sue attivita'.\n\n");
            if (waiting.isEmpty()) {
                body.append("Al momento non ha attivita' in attesa.\n");
            } else {
                body.append("Attivita' gia' in attesa (").append(waiting.size()).append("):\n");
                body.append(waiting.stream().limit(30)
                        .map(w -> " - " + w.processTypeText() + " " + w.documentKey() + ": " + w.taskText())
                        .collect(Collectors.joining("\n"))).append('\n');
                if (waiting.size() > 30) body.append(" ... e altre ").append(waiting.size() - 30).append('\n');
            }
            String base = AppConfig.get("EWF_APP_BASE_URL", null);
            if (base != null && !base.isBlank())
                body.append("\nAccesso: ").append(base.replaceAll("/+$", "")).append("/Outest.risc\n");
            MailMessage m = MailMessage.of("EWF_SUBSTITUTION", to.get().email(),
                    "eWF - sostituzione di " + s.userName(), body.toString())
                    .withKey(String.valueOf(s.id()), "SUBST-" + s.id());
            new MailOutbox(new Db(EwfApp.get().dataSource())).enqueue(m);
            return true;
        } catch (RuntimeException e) {
            System.err.println("[AutoSubstitutionUI] Mail al sostituto non accodata: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ binding

    public boolean isCanManage() { return m_canManage; }
    public boolean isNotAllowed() { return !m_canManage; }
    public boolean isHasExisting() { return m_existing != null; }
    public String getExistingSummary() {
        if (m_existing == null) return "";
        return (m_existing.activeToday() ? "Sostituzione in corso: " : "Sostituzione programmata: ")
                + m_existing.substituteName() + " dal " + DAY.format(m_existing.from()) + " al " + DAY.format(m_existing.to());
    }
    public boolean isHasCovering() { return !m_covering.isEmpty(); }
    public String getCoveringMessage() {
        return "Stai sostituendo: " + m_covering.stream()
                .map(c -> c.userName() + " (dal " + DAY.format(c.from()) + " al " + DAY.format(c.to()) + ")")
                .collect(Collectors.joining(", "));
    }
    public ValidValuesBinding getSubstituteVVB() { return m_substituteVVB; }
    public String getSubstitute() { return m_substitute; }
    public void setSubstitute(String v) { m_substitute = v == null || v.isBlank() ? null : v; }
    public LocalDate getFrom() { return m_from; }
    public void setFrom(LocalDate v) { m_from = v; }
    public LocalDate getTo() { return m_to; }
    public void setTo(LocalDate v) { m_to = v; }
}
