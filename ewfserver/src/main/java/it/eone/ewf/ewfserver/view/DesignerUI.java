package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.DesignerService;
import it.eone.ewf.service.DesignerService.Problem;
import it.eone.ewf.service.DesignerService.StepRow;
import it.eone.ewf.service.DesignerService.TypeRow;
import it.eone.ewf.service.DesignerService.VersionRow;
import it.eone.ewf.service.DesignerService.VersionView;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeSet;

/**
 * Disegno processi (amministratori), primo passo: consultazione. Tipi processo, loro versioni e, per la versione
 * scelta, diagramma del flusso ({@link FlowSvg}) e problemi della validazione.
 */
@CCGenClass(expressionBase = "#{d.DesignerUI}")
public class DesignerUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private IBackToMenu m_back;
    private final FIXGRIDListBinding<TypeItem> m_types = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<VersionItem> m_versions = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<ProblemItem> m_problems = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<StepItem> m_steps = new FIXGRIDListBinding<>();

    private String m_type;
    private String m_typeDescription;
    private Long m_versionId;
    private VersionView m_view;
    private String m_svg;
    private int m_svgWidth = 800;
    private int m_svgHeight = 400;

    public DesignerUI() {
        SessionGuard.requireUser("/Designer.xml");
    }

    @Override public String getPageName() { return "/Designer.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.DesignerUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static DesignerService designer() { return EwfApp.get().designer(); }

    public void prepare(IBackToMenu back) {
        m_back = back;
        reloadTypes();
    }

    private void reloadTypes() {
        m_types.getItems().clear();
        try {
            for (TypeRow t : designer().types(uid())) {
                TypeItem it = new TypeItem(t);
                m_types.getItems().add(it);
                if (t.code().equals(m_type)) m_types.selectItem(it);
            }
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
        reloadVersions();
    }

    private void reloadVersions() {
        m_versions.getItems().clear();
        if (m_type == null) return;
        try {
            VersionItem preferred = null;
            for (VersionRow v : designer().versions(uid(), m_type)) {
                VersionItem it = new VersionItem(v);
                m_versions.getItems().add(it);
                if (m_versionId != null && v.id() == m_versionId) preferred = it;
            }
            if (preferred == null)        // alla prima apertura: la versione attiva, altrimenti la piu' recente
                for (VersionItem it : m_versions.getItems())
                    if ("ACTIVE".equals(it.v.status())) preferred = it;
            if (preferred == null && !m_versions.getItems().isEmpty()) preferred = m_versions.getItems().get(0);
            if (preferred != null) {
                m_versions.selectItem(preferred);
                openVersion(preferred.v.id());
            } else {
                clearVersion();
            }
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    private void openVersion(long id) {
        m_versionId = id;
        m_problems.getItems().clear();
        m_steps.getItems().clear();
        m_svg = null;
        try {
            m_view = designer().view(uid(), id);
            if (isDraft()) for (StepRow r : designer().steps(uid(), id)) m_steps.getItems().add(new StepItem(r));
            for (Problem p : m_view.problems()) m_problems.getItems().add(new ProblemItem(p));
            if (m_view.definition() != null) {
                Set<Integer> bad = new TreeSet<>();
                for (Problem p : m_view.problems()) if (p.isError() && p.stepNo() != null) bad.add(p.stepNo());
                FlowSvg.Result r = FlowSvg.render(m_view.definition(),
                        m_view.row().processType() + " – " + m_view.typeDescription() + " · versione "
                                + m_view.row().versionNo(), bad);
                m_svg = r.svg();
                m_svgWidth = r.width();
                m_svgHeight = r.height();
            }
        } catch (ServiceException e) {
            m_view = null;
            Statusbar.outputError(e.getMessage());
        }
    }

    private void clearVersion() {
        m_versionId = null;
        m_view = null;
        m_svg = null;
        m_problems.getItems().clear();
        m_steps.getItems().clear();
    }

    /** Dopo una modifica: righe dei tipi e delle versioni aggiornate, la stessa versione riaperta. */
    private void refreshAfterChange() {
        reloadTypes();
    }

    // ------------------------------------------------------------------ azioni

    public void onBackToMenu(ActionEvent ae) { if (m_back != null) m_back.backToMenu(); }

    public void onRefresh(ActionEvent ae) { reloadTypes(); }

    public void onNewVersion(ActionEvent ae) {
        if (m_view == null) return;
        VersionRow from = m_view.row();
        YESNOPopup.createInstance("Nuova versione", "Creare una nuova bozza di " + from.processType()
                + " copiando la versione " + from.versionNo() + "?", new YESNOPopup.IYesNoListener() {
            @Override public void reactOnYes() {
                try {
                    m_versionId = designer().newVersion(uid(), from.id());
                    Statusbar.outputSuccess("Bozza creata");
                    refreshAfterChange();
                } catch (ServiceException e) {
                    Statusbar.outputError(e.getMessage());
                }
            }
            @Override public void reactOnNo() { }
        });
    }

    public void onDeleteDraft(ActionEvent ae) {
        if (!isDraft()) return;
        VersionRow v = m_view.row();
        YESNOPopup.createInstance("Elimina bozza", "Eliminare la bozza " + v.versionNo() + " di " + v.processType()
                + " con tutto il suo contenuto?", new YESNOPopup.IYesNoListener() {
            @Override public void reactOnYes() {
                try {
                    designer().deleteDraft(uid(), v.id());
                    m_versionId = null;
                    Statusbar.outputSuccess("Bozza eliminata");
                    refreshAfterChange();
                } catch (ServiceException e) {
                    Statusbar.outputError(e.getMessage());
                }
            }
            @Override public void reactOnNo() { }
        });
    }

    public void onActivate(ActionEvent ae) {
        if (!isDraft()) return;
        if (m_view.hasErrors()) {
            Statusbar.outputError("La validazione ha trovato errori: correggerli prima di attivare");
            return;
        }
        VersionRow v = m_view.row();
        YESNOPopup.createInstance("Attiva versione", "Attivare la versione " + v.versionNo() + " di " + v.processType()
                + "? I nuovi processi useranno questa; quelli gia' in corso restano sulla loro versione.",
                new YESNOPopup.IYesNoListener() {
                    @Override public void reactOnYes() {
                        try {
                            designer().activate(uid(), v.id());
                            Statusbar.outputSuccess("Versione " + v.versionNo() + " attiva");
                            refreshAfterChange();
                        } catch (ServiceException e) {
                            Statusbar.outputError(e.getMessage());
                        }
                    }
                    @Override public void reactOnNo() { }
                });
    }

    public void onVersionSettings(ActionEvent ae) {
        if (!isDraft()) return;
        VersionEditUI ui = new VersionEditUI();
        ui.prepare(m_versionId, new TaskEditUI.IListener() {
            @Override public void saved() {
                closePopup(ui);
                openVersion(m_versionId);
            }
            @Override public void cancelled() { closePopup(ui); }
        });
        openModalPopup(ui, "Azione finale e nota", 560, 320, new ModalPopup.IModalPopupListener() {
            @Override public void reactOnPopupClosedByUser() { closePopup(ui); }
        });
    }

    public void onNewStep(ActionEvent ae) { openStep(null); }

    private void openStep(StepRow r) {
        if (!isDraft()) return;
        StepEditUI ui = new StepEditUI();
        ui.prepare(m_versionId, r == null ? null : r.id(), new StepEditUI.IListener() {
            @Override public void changed() { openVersion(m_versionId); }
            @Override public void close() {
                closePopup(ui);
                refreshAfterChange();
            }
        });
        openModalPopup(ui, r == null ? "Nuovo step" : "Step " + r.stepNo() + (r.option() == null ? "" : " (ramo " + r.option() + ")"),
                760, 600, new ModalPopup.IModalPopupListener() {
                    @Override public void reactOnPopupClosedByUser() {
                        closePopup(ui);
                        refreshAfterChange();
                    }
                });
    }

    // ------------------------------------------------------------------ binding

    public FIXGRIDListBinding<TypeItem> getTypes() { return m_types; }
    public FIXGRIDListBinding<VersionItem> getVersions() { return m_versions; }
    public FIXGRIDListBinding<ProblemItem> getProblems() { return m_problems; }
    public FIXGRIDListBinding<StepItem> getSteps() { return m_steps; }
    public boolean isDraft() { return m_view != null && "DRAFT".equals(m_view.row().status()); }
    public boolean isReadOnlyVersion() { return m_view != null && !isDraft(); }
    public boolean isTypeSelected() { return m_type != null; }
    public String getVersionsTitle() { return "Versioni di " + m_type; }
    public boolean isVersionSelected() { return m_view != null; }
    public boolean isNoVersion() { return m_view == null; }
    public boolean isWithProblems() { return m_view != null && !m_view.problems().isEmpty(); }
    public boolean isNoProblems() { return m_view != null && m_view.problems().isEmpty(); }
    public boolean isWithDiagram() { return m_svg != null; }
    public String getSvg() { return m_svg; }
    public int getSvgWidth() { return m_svgWidth; }
    public int getSvgHeight() { return m_svgHeight; }

    public String getVersionTitle() {
        if (m_view == null) return "";
        VersionRow v = m_view.row();
        return v.processType() + " – " + m_typeDescription + " · versione " + v.versionNo() + " · " + statusText(v.status());
    }

    public String getVersionInfo() {
        if (m_view == null) return "";
        VersionRow v = m_view.row();
        StringBuilder b = new StringBuilder("Creata il ").append(dayTime(v.createdAt()));
        if (v.createdBy() != null) b.append(" da ").append(v.createdBy());
        if (v.activatedAt() != null) b.append(" · attivata il ").append(dayTime(v.activatedAt()));
        b.append(" · ").append(v.steps()).append(" step");
        b.append(" · processi in corso su questa versione: ").append(v.running());
        if (v.note() != null && !v.note().isBlank()) b.append(" · ").append(v.note());
        return b.toString();
    }

    static String statusText(String s) {
        return switch (s) {
            case "ACTIVE" -> "attiva";
            case "DRAFT" -> "bozza";
            case "RETIRED" -> "ritirata";
            default -> s;
        };
    }

    private static String day(Instant i) {
        return i == null ? "" : DAY.format(i.atZone(EwfApp.get().zone()));
    }

    private static String dayTime(Instant i) {
        return i == null ? "" : DAY_TIME.format(i.atZone(EwfApp.get().zone()));
    }

    // ------------------------------------------------------------------ righe

    public class TypeItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TypeRow t;
        TypeItem(TypeRow t) { this.t = t; }
        @Override public void onRowSelect() {
            m_type = t.code();
            m_typeDescription = t.description();
            m_versionId = null;
            reloadVersions();
        }
        public String getCode() { return t.code(); }
        public String getDescription() { return t.description() + (t.suspended() ? " (sospeso)" : ""); }
        public String getActive() { return t.activeVersionNo() == null ? "–" : "v" + t.activeVersionNo(); }
        public String getActiveColor() { return t.activeVersionNo() == null ? "#FFCDD2" : "#C8E6C9"; }
        public String getDrafts() { return t.drafts() == 0 ? "" : String.valueOf(t.drafts()); }
        public String getRunning() { return t.running() == 0 ? "" : String.valueOf(t.running()); }
    }

    public class VersionItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final VersionRow v;
        VersionItem(VersionRow v) { this.v = v; }
        @Override public void onRowSelect() { openVersion(v.id()); }
        public String getVersionNo() { return String.valueOf(v.versionNo()); }
        public String getStatus() { return statusText(v.status()); }
        public String getStatusColor() {
            return switch (v.status()) {
                case "ACTIVE" -> "#C8E6C9";
                case "DRAFT" -> "#FFF59D";
                default -> "#E0E0E0";
            };
        }
        public String getCreated() { return day(v.createdAt()); }
        public String getActivated() { return day(v.activatedAt()); }
        public String getSteps() { return String.valueOf(v.steps()); }
        public String getRunning() { return v.running() == 0 ? "" : String.valueOf(v.running()); }
    }

    public class StepItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final StepRow r;
        StepItem(StepRow r) { this.r = r; }
        public String getStepNo() { return r.stepNo() + (r.first() ? " \u25b6" : ""); }
        public String getOption() { return r.option() == null ? "" : r.option(); }
        public String getTasks() {
            if (r.tasks().isEmpty()) return "(nessun task)";
            String t = r.tasks().get(0).text();
            return r.tasks().size() == 1 ? t : t + "  + altri " + (r.tasks().size() - 1) + " in parallelo";
        }
        public String getNextOk() { return r.nextOk() == null ? "fine" : "step " + r.nextOk(); }
        public String getNextKo() { return r.nextKo() == null ? "fine" : "step " + r.nextKo(); }
        public String getThreshold() {
            return r.minAmount() == null ? "" : "\u2265 " + FlowSvg.money(new it.eone.ewf.engine.model.Money(r.minAmount(),
                    r.currency() == null ? "EUR" : r.currency()));
        }
        public void onEdit(ActionEvent ae) { openStep(r); }
        public void onDelete(ActionEvent ae) {
            YESNOPopup.createInstance("Elimina step", "Eliminare lo step " + r.stepNo() + " con i suoi task?"
                    + " Le frecce che vi puntano andranno sistemate (le segnala la validazione).", new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() {
                    try {
                        designer().deleteStep(uid(), m_versionId, r.id());
                        refreshAfterChange();
                    } catch (ServiceException e) {
                        Statusbar.outputError(e.getMessage());
                    }
                }
                @Override public void reactOnNo() { }
            });
        }
    }

    public class ProblemItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Problem p;
        ProblemItem(Problem p) { this.p = p; }
        public String getSeverity() { return p.isError() ? "Errore" : "Avviso"; }
        public String getColor() { return p.isError() ? "#FFCDD2" : "#FFF59D"; }
        public String getStep() { return p.stepNo() == null ? "" : String.valueOf(p.stepNo()); }
        public String getText() { return p.text(); }
    }
}
