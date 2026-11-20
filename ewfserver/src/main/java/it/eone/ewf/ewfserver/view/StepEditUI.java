package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.DesignerService;
import it.eone.ewf.service.DesignerService.Choice;
import it.eone.ewf.service.DesignerService.StepEdit;
import it.eone.ewf.service.DesignerService.StepRow;
import it.eone.ewf.service.DesignerService.TaskRow;
import it.eone.ewf.service.ServiceException;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.ModalPopup;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.TreeSet;

/**
 * Modifica di uno step di una bozza (finestra sopra il designer): numero, ramo, primo step, frecce di approvazione
 * e rifiuto, soglia di valore, task paralleli. I task si modificano in {@link TaskEditUI}.
 */
@CCGenClass(expressionBase = "#{d.StepEditUI}")
public class StepEditUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Avvisato a ogni salvataggio (per ridisegnare) e alla chiusura. */
    public interface IListener extends Serializable {
        void changed();
        void close();
    }

    private IListener m_listener;
    private long m_versionId;
    private Long m_stepId;
    private final ValidValuesBinding m_stepsVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_optionsVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_joinVVB = new ValidValuesBinding();
    private final FIXGRIDListBinding<TaskItem> m_tasks = new FIXGRIDListBinding<>();

    private String m_stepNo;
    private String m_option;
    private boolean m_first;
    private String m_join = "ALL";
    private String m_nextOk;
    private String m_nextKo;
    private String m_minAmount;
    private String m_currency;

    public StepEditUI() {
        SessionGuard.requireUser("/StepEdit.xml");
        m_joinVVB.addValidValue("ALL", "devono approvare tutti");
        m_joinVVB.addValidValue("ANY", "basta un'approvazione");
    }

    @Override public String getPageName() { return "/StepEdit.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.StepEditUI}"; }

    private static long uid() { return EwfSession.instance().userId(); }
    private static DesignerService designer() { return EwfApp.get().designer(); }

    /** stepId null = nuovo step (proposto il numero dopo l'ultimo). */
    public void prepare(long versionId, Long stepId, IListener listener) {
        m_versionId = versionId;
        m_stepId = stepId;
        m_listener = listener;
        load();
    }

    private void load() {
        try {
            TreeSet<Integer> numbers = new TreeSet<>();
            StepRow mine = null;
            for (StepRow s : designer().steps(uid(), m_versionId)) {
                numbers.add(s.stepNo());
                if (m_stepId != null && s.id() == m_stepId) mine = s;
            }
            m_stepsVVB.clear();
            m_stepsVVB.addValidValue("", "fine del processo");
            for (int n : numbers) m_stepsVVB.addValidValue(String.valueOf(n), "step " + n);
            m_optionsVVB.clear();
            m_optionsVVB.addValidValue("", "tronco comune");
            for (Choice c : designer().optionCodes(uid(), m_versionId))
                m_optionsVVB.addValidValue(c.code(), "ramo " + c.code() + " – " + c.text());
            m_tasks.getItems().clear();
            if (mine == null) {
                int next = numbers.isEmpty() ? 10 : (numbers.last() / 10 + 1) * 10;
                m_stepNo = String.valueOf(next);
                m_first = numbers.isEmpty();
                m_nextOk = "";
                m_nextKo = "";
                return;
            }
            m_stepNo = String.valueOf(mine.stepNo());
            m_option = mine.option();
            m_first = mine.first();
            m_join = mine.join();
            m_nextOk = mine.nextOk() == null ? "" : String.valueOf(mine.nextOk());
            m_nextKo = mine.nextKo() == null ? "" : String.valueOf(mine.nextKo());
            m_minAmount = mine.minAmount() == null ? null : mine.minAmount().stripTrailingZeros().toPlainString();
            m_currency = mine.currency();
            for (TaskRow t : mine.tasks()) m_tasks.getItems().add(new TaskItem(t));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ azioni

    public void onSave(ActionEvent ae) {
        try {
            int no;
            try {
                no = Integer.parseInt(m_stepNo.trim());
            } catch (RuntimeException e) {
                Statusbar.outputError("Numero dello step non valido");
                return;
            }
            BigDecimal min = null;
            if (m_minAmount != null && !m_minAmount.isBlank()) {
                try {
                    min = new BigDecimal(m_minAmount.trim().replace(".", "").replace(',', '.'));
                } catch (NumberFormatException e) {
                    Statusbar.outputError("Soglia non valida (es. 10000 oppure 10.000,50)");
                    return;
                }
            }
            m_stepId = designer().saveStep(uid(), m_versionId, new StepEdit(m_stepId, no, m_option, m_first, m_join,
                    intOrNull(m_nextOk), intOrNull(m_nextKo), min, m_currency));
            Statusbar.outputSuccess("Step " + no + " salvato");
            load();
            if (m_listener != null) m_listener.changed();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onClose(ActionEvent ae) { if (m_listener != null) m_listener.close(); }

    public void onNewTask(ActionEvent ae) {
        if (m_stepId == null) {
            Statusbar.outputError("Salvare prima lo step");
            return;
        }
        openTask(null);
    }

    private void openTask(TaskRow t) {
        TaskEditUI ui = new TaskEditUI();
        ui.prepare(m_versionId, m_stepId, t, new TaskEditUI.IListener() {
            @Override public void saved() {
                load();
                if (m_listener != null) m_listener.changed();
            }
            @Override public void cancelled() { closePopup(ui); }
        });
        openModalPopup(ui, t == null ? "Nuovo task dello step " + m_stepNo : "Task: " + t.text(), 900, 760,
                new ModalPopup.IModalPopupListener() {
                    @Override public void reactOnPopupClosedByUser() { closePopup(ui); }
                });
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        return Integer.valueOf(s.trim());
    }

    // ------------------------------------------------------------------ binding

    public ValidValuesBinding getStepsVVB() { return m_stepsVVB; }
    public ValidValuesBinding getOptionsVVB() { return m_optionsVVB; }
    public ValidValuesBinding getJoinVVB() { return m_joinVVB; }
    public FIXGRIDListBinding<TaskItem> getTasks() { return m_tasks; }
    public boolean isExisting() { return m_stepId != null; }
    public boolean isNotExisting() { return m_stepId == null; }
    public String getStepNo() { return m_stepNo; }
    public void setStepNo(String v) { m_stepNo = v; }
    public String getOption() { return m_option == null ? "" : m_option; }
    public void setOption(String v) { m_option = v == null || v.isBlank() ? null : v; }
    public boolean isFirst() { return m_first; }
    public void setFirst(boolean v) { m_first = v; }
    public String getJoin() { return m_join; }
    public void setJoin(String v) { m_join = v == null ? "ALL" : v; }
    public String getNextOk() { return m_nextOk; }
    public void setNextOk(String v) { m_nextOk = v; }
    public String getNextKo() { return m_nextKo; }
    public void setNextKo(String v) { m_nextKo = v; }
    public String getMinAmount() { return m_minAmount; }
    public void setMinAmount(String v) { m_minAmount = v; }
    public String getCurrency() { return m_currency; }
    public void setCurrency(String v) { m_currency = v; }

    // ------------------------------------------------------------------ righe

    public class TaskItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final TaskRow t;
        TaskItem(TaskRow t) { this.t = t; }
        public String getText() { return t.text(); }
        public String getWho() { return TaskEditUI.whoText(t); }
        public String getWeight() { return t.requiredWeight() == 0 ? "" : String.valueOf(t.requiredWeight()); }
        public String getExtras() {
            StringBuilder b = new StringBuilder();
            if (t.hooks() > 0) b.append(t.hooks()).append(" agganci ");
            if (t.options() > 0) b.append(t.options()).append(" opzioni ");
            if (t.functions() > 0) b.append(t.functions()).append(" funzioni");
            return b.toString().trim();
        }
        public void onEdit(ActionEvent ae) { openTask(t); }
        public void onDelete(ActionEvent ae) {
            YESNOPopup.createInstance("Elimina task", "Eliminare il task “" + t.text() + "” con i suoi agganci,"
                    + " opzioni e funzioni?", new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() {
                    try {
                        designer().deleteTask(uid(), m_versionId, t.id());
                        load();
                        if (m_listener != null) m_listener.changed();
                    } catch (ServiceException e) {
                        Statusbar.outputError(e.getMessage());
                    }
                }
                @Override public void reactOnNo() { }
            });
        }
    }
}
