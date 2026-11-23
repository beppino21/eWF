package it.eone.ewf.ewfserver.view;

import it.eone.ewf.engine.Requests;
import it.eone.ewf.engine.Requests.PathEntry;
import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.*;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.elements.events.BaseActionEventUpload;
import org.eclnt.jsfserver.elements.util.Trigger;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.jsfserver.util.tempfile.TempFileManager;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Versione da telefono del dettaglio: dati essenziali, percorso, allegati e commenti a richiesta, e le
 * azioni sul proprio task (approva, respingi, oppure scelta tra le opzioni del nodo). L'azione si conferma
 * in una seconda schermata con il commento. Stessi servizi del dettaglio desktop (TaskDetailUI).
 */
@CCGenClass(expressionBase = "#{d.MobileTaskUI}")
public class MobileTaskUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public interface IListener extends Serializable {
        /** Torna alla lista (anche dopo un'azione riuscita). */
        void reactOnBack();
    }

    private enum Action { APPROVE, REJECT, CHOOSE }

    private IListener m_listener;
    private long m_processId;
    private ProcessDetail m_detail;
    private Action m_action;
    private String m_option;
    private String m_note;

    private final List<Field> m_fields = new ArrayList<>();
    private final List<Step> m_path = new ArrayList<>();
    private final List<CommentRow> m_comments = new ArrayList<>();
    private final List<AttachmentRow> m_attachments = new ArrayList<>();
    private final List<OptionRow> m_options = new ArrayList<>();
    private final List<SheetRow> m_sheets = new ArrayList<>();
    /** Dati del documento: si vedono i primi FIELDS_SHORT, il resto a richiesta. */
    private static final int FIELDS_SHORT = 4;
    private boolean m_fieldsOpen;
    private boolean m_canUpload;
    private String m_newComment;
    private int m_attachmentCount;
    private boolean m_attachmentsOpen;
    private boolean m_commentsOpen;
    private String m_attachmentsMessage;

    private final Trigger m_downloadTrigger = new Trigger();
    private String m_downloadUrl;

    public MobileTaskUI() {
        SessionGuard.requireUser("/MobileTask.xml");
    }

    @Override public String getPageName() { return "/MobileTask.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.MobileTaskUI}"; }

    public void prepare(long processId, IListener listener) {
        m_processId = processId;
        m_listener = listener;
        load();
    }

    private EwfService svc() { return EwfApp.get().service(); }
    private long uid() { return EwfSession.instance().userId(); }

    private void load() {
        m_action = null;
        m_attachmentsOpen = false;
        m_commentsOpen = false;
        m_attachments.clear();
        try {
            m_detail = svc().detail(uid(), m_processId);
        } catch (ServiceException e) {
            m_detail = null;
            Statusbar.outputError(e.getMessage());
            return;
        }
        Header h = m_detail.header();
        m_fields.clear();
        if (h.value() != null) addField("Valore", EwfService.formatAmount(h.value().amount()) + " " + h.value().currency());
        addField("Avviato da", h.starter());
        addField("Avviato il", TaskDetailUI.ts(h.startedAt()));
        if (h.text() != null) addField("Descrizione", h.text());
        for (Map.Entry<String, String> a : h.attributes().entrySet())
            if (a.getKey().startsWith("sap.")) addField(a.getKey().substring(4), a.getValue());
        m_path.clear();
        for (PathEntry p : m_detail.path()) m_path.add(new Step(p));
        m_comments.clear();
        for (Comment c : m_detail.comments()) m_comments.add(new CommentRow(c));
        m_sheets.clear();
        for (SheetView v : m_detail.links().sheets()) m_sheets.add(new SheetRow(v));
        try {
            m_canUpload = svc().canAddAttachments(uid(), m_processId);
        } catch (ServiceException e) {
            m_canUpload = false;
        }
        m_options.clear();
        MyTask t = myTask();
        if (t != null) for (Option o : t.options()) m_options.add(new OptionRow(o));
        try {
            m_attachmentCount = svc().infoCounts(uid(), List.of(m_processId))
                    .getOrDefault(m_processId, InfoCounts.NONE).attachments();
        } catch (ServiceException e) {
            m_attachmentCount = -1;
        }
    }

    private void addField(String label, String value) {
        if (value != null && !value.isBlank()) m_fields.add(new Field(label, value));
    }

    private MyTask myTask() { return m_detail == null ? null : m_detail.myTask(); }

    // ------------------------------------------------------------------ navigazione e sezioni

    public void onBack(ActionEvent ae) { if (m_listener != null) m_listener.reactOnBack(); }

    public void onToggleAttachments(ActionEvent ae) {
        m_attachmentsOpen = !m_attachmentsOpen;
        if (m_attachmentsOpen) loadAttachments();
    }

    public void onToggleComments(ActionEvent ae) { m_commentsOpen = !m_commentsOpen; }

    private void loadAttachments() {
        m_attachments.clear();
        m_attachmentsMessage = null;
        try {
            AttachmentList list = svc().attachmentList(uid(), m_processId);
            for (Attachment a : list.items()) m_attachments.add(new AttachmentRow(a));
            m_attachmentsMessage = list.sourceWarning();
            if (m_attachments.isEmpty() && m_attachmentsMessage == null) m_attachmentsMessage = "Nessun allegato.";
        } catch (ServiceException e) {
            m_attachmentsMessage = e.getMessage();
        }
    }

    private void openSheet(String code) {
        try {
            download(svc().documentSheet(uid(), m_processId, code));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[MobileTaskUI] Errore scheda " + code + " processo " + m_processId + ": " + e);
            e.printStackTrace();
            Statusbar.outputError("Impossibile generare la scheda: vedere il log del server.");
        }
    }

    public void onToggleFields(ActionEvent ae) { m_fieldsOpen = !m_fieldsOpen; }

    public void onAddComment(ActionEvent ae) {
        try {
            svc().addComment(uid(), m_processId, m_newComment);
            m_newComment = null;
            m_comments.clear();
            for (Comment c : svc().detail(uid(), m_processId).comments()) m_comments.add(new CommentRow(c));
            Statusbar.outputSuccess("Commento aggiunto");
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    /** Dal telefono: un file o una foto scattata al momento (il browser propone la fotocamera). */
    public void onUpload(ActionEvent ae) {
        if (!m_canUpload || !(ae instanceof BaseActionEventUpload up)) return;
        int added = 0;
        for (int i = 0; i < up.getNumberOfUploadedFiles(); i++) {
            String name = up.getClientFileName(i);
            String hex = up.getHexByteString(i);
            if (hex == null || hex.isEmpty()) continue;
            try {
                svc().addAttachment(uid(), m_processId, name, TaskDetailUI.mimeOf(name), HexFormat.of().parseHex(hex));
                added++;
            } catch (ServiceException e) {
                Statusbar.outputError(name + ": " + e.getMessage());
            }
        }
        loadAttachments();
        if (added > 0) {
            if (m_attachmentCount >= 0) m_attachmentCount += added;
            Statusbar.outputSuccess(added == 1 ? "Allegato aggiunto" : added + " allegati aggiunti");
        }
    }

    private void download(FileContent f) {
        m_downloadUrl = TempFileManager.saveTempFile(f.fileName(), f.mimeType(), f.content());
        m_downloadTrigger.trigger();
    }

    // ------------------------------------------------------------------ azione

    public void onStartApprove(ActionEvent ae) { start(Action.APPROVE); }
    public void onStartReject(ActionEvent ae) { start(Action.REJECT); }
    public void onStartChoose(ActionEvent ae) { start(Action.CHOOSE); }

    private void start(Action a) {
        if (!isCanAct()) return;
        m_action = a;
        m_option = null;
    }

    public void onCancelAction(ActionEvent ae) { m_action = null; }

    public void onConfirm(ActionEvent ae) {
        MyTask t = myTask();
        if (t == null || m_action == null) return;
        if (m_action == Action.CHOOSE && m_option == null) {
            Statusbar.outputError("Scegliere come proseguire");
            return;
        }
        try {
            Object r = switch (m_action) {
                case APPROVE -> svc().approve(uid(), t.taskId(), m_note, t.rowVersion());
                case REJECT -> svc().reject(uid(), t.taskId(), m_note, t.rowVersion());
                case CHOOSE -> svc().choose(uid(), t.taskId(), m_option, m_note, t.rowVersion());
            };
            // Come nel dettaglio desktop: un'azione in SAP non riuscita non e' un'eccezione ma un esito.
            if (r instanceof Requests.Outcome o && o.result() != Requests.Outcome.Result.DONE) {
                String msg = o.message() == null ? "" : o.message();
                Statusbar.outputError(o.result() == Requests.Outcome.Result.REJECTED
                        ? "Operazione non eseguita: " + msg
                        : "Esito incerto in SAP, processo in errore (da verificare): " + msg);
                load();
                return;
            }
            String ok = switch (m_action) {
                case APPROVE -> "Approvato";
                case REJECT -> "Respinto";
                case CHOOSE -> "Scelta registrata";
            };
            m_note = null;
            Statusbar.outputSuccess(ok + ": " + m_detail.header().documentKey());
            if (m_listener != null) m_listener.reactOnBack();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
            if (e.code() == ServiceException.Code.INVALID_STATE) load();   // un collega e' arrivato prima
        }
    }

    // ------------------------------------------------------------------ binding

    public boolean isLoaded() { return m_detail != null; }
    public boolean isDetailMode() { return m_detail != null && m_action == null; }
    public boolean isActionMode() { return m_detail != null && m_action != null; }
    public boolean isCanAct() { return myTask() != null && myTask().webActionable(); }
    public boolean isPlainDecision() { return isDetailMode() && isCanAct() && m_options.isEmpty(); }
    public boolean isHasOptions() { return isDetailMode() && isCanAct() && !m_options.isEmpty(); }
    public boolean isSapGuiOnly() { return isDetailMode() && myTask() != null && !myTask().webActionable(); }
    public boolean isNoTask() { return isDetailMode() && myTask() == null; }
    public boolean isChoosing() { return m_action == Action.CHOOSE; }
    public boolean isHasSheet() { return isDetailMode() && !m_sheets.isEmpty(); }
    public List<SheetRow> getSheets() { return m_sheets; }
    public List<Field> getVisibleFields() {
        return m_fieldsOpen || m_fields.size() <= FIELDS_SHORT ? m_fields : m_fields.subList(0, FIELDS_SHORT);
    }
    public boolean isFieldsToggle() { return m_fields.size() > FIELDS_SHORT; }
    public String getFieldsToggleText() {
        return m_fieldsOpen ? "Mostra meno" : "Mostra tutti i dati (" + m_fields.size() + ")";
    }
    public boolean isUploadAllowed() { return isAttachmentsOpen() && m_canUpload; }
    public String getNewComment() { return m_newComment; }
    public void setNewComment(String v) { m_newComment = v; }

    public String getKicker() {
        if (m_detail == null) return "";
        MyTask t = myTask();
        String task = t == null ? TaskDetailUI.statusText(m_detail.header().status()) : t.taskText();
        return m_detail.header().processTypeText() + " · " + task;
    }
    public String getTitle() { return m_detail == null ? "Dettaglio" : m_detail.header().documentKey(); }
    public String getForWhom() {
        MyTask t = myTask();
        if (t == null) return "";
        return t.reason() == Reason.SUBSTITUTE ? "Per conto di " + t.forWhom()
                : t.reason() == Reason.TEAM ? "Attivita' del team " + t.forWhom() : "";
    }
    public boolean isForWhomVisible() { return !getForWhom().isEmpty(); }

    public String getActionTitle() {
        if (m_action == null) return "";
        return switch (m_action) {
            case APPROVE -> "Approva";
            case REJECT -> "Respingi";
            case CHOOSE -> "Come proseguire";
        };
    }
    public String getActionSubtitle() {
        if (m_detail == null) return "";
        Header h = m_detail.header();
        String v = h.value() == null ? "" : " · " + EwfService.formatAmount(h.value().amount()) + " " + h.value().currency();
        return h.documentKey() + v;
    }
    public String getConfirmText() {
        if (m_action == null) return "";
        return switch (m_action) {
            case APPROVE -> "Conferma approvazione";
            case REJECT -> "Conferma rifiuto";
            case CHOOSE -> "Conferma scelta";
        };
    }
    public String getConfirmBg() { return m_action == Action.REJECT ? "#B71C1C" : "#1565C0"; }
    public String getNoteHint() {
        return m_action == Action.REJECT ? "Commento (consigliato: spiega il motivo del rifiuto)" : "Commento (facoltativo)";
    }

    public String getAttachmentsText() {
        return m_attachmentCount > 0 ? "Allegati (" + m_attachmentCount + ")" : "Allegati";
    }
    public String getCommentsText() {
        return m_comments.isEmpty() ? "Commenti" : "Commenti (" + m_comments.size() + ")";
    }
    public String getAttachmentsBg() { return m_attachmentsOpen ? "#E3F0FB" : "#FFFFFF"; }
    public String getCommentsBg() { return m_commentsOpen ? "#E3F0FB" : "#FFFFFF"; }
    public boolean isAttachmentsOpen() { return isDetailMode() && m_attachmentsOpen; }
    public boolean isCommentsOpen() { return isDetailMode() && m_commentsOpen; }
    public boolean isAttachmentsMessageVisible() { return m_attachmentsMessage != null; }
    public String getAttachmentsMessage() { return m_attachmentsMessage; }
    public boolean isNoComments() { return m_comments.isEmpty(); }

    public List<Field> getFields() { return m_fields; }
    public List<Step> getPath() { return m_path; }
    public List<CommentRow> getComments() { return m_comments; }
    public List<AttachmentRow> getAttachments() { return m_attachments; }
    public List<OptionRow> getOptions() { return m_options; }

    public String getNote() { return m_note; }
    public void setNote(String v) { m_note = v; }
    public Trigger getDownloadTrigger() { return m_downloadTrigger; }
    public String getDownloadUrl() { return m_downloadUrl; }

    // ------------------------------------------------------------------ righe

    public static class Field implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String label, value;
        Field(String label, String value) { this.label = label; this.value = value; }
        public String getLabel() { return label; }
        public String getValue() { return value; }
    }

    public static class Step implements Serializable {
        private static final long serialVersionUID = 1L;
        private final PathEntry p;
        Step(PathEntry p) { this.p = p; }
        public String getMarker() {
            return switch (p.kind()) {
                case DONE -> " ✓ ";
                case CURRENT -> " " + p.stepNo() + " ";
                case PLANNED -> " " + p.stepNo() + " ";
                case CHOICE -> " ? ";
            };
        }
        public String getMarkerBg() {
            return switch (p.kind()) {
                case DONE -> "#2E7D32";
                case CURRENT -> "#1565C0";
                case PLANNED -> "#CFD8DC";
                case CHOICE -> "#BBDEFB";
            };
        }
        public String getMarkerColor() {
            return p.kind() == PathEntry.Kind.DONE || p.kind() == PathEntry.Kind.CURRENT ? "#FFFFFF" : "#37474F";
        }
        public String getMarkerFont() {
            return p.kind() == PathEntry.Kind.DONE || p.kind() == PathEntry.Kind.CURRENT
                    ? "size:13;weight:bold;color:#FFFFFF" : "size:13;weight:bold;color:#37474F";
        }
        public String getText() {
            String who = p.kind() == PathEntry.Kind.CHOICE ? String.join(" / ", p.options()) : TaskDetailUI.nvl(p.who());
            return who.isEmpty() ? p.taskText() : p.taskText() + " · " + who;
        }
        public String getTextColor() {
            return p.kind() == PathEntry.Kind.PLANNED || p.kind() == PathEntry.Kind.CHOICE ? "#546E7A" : "#1F2A33";
        }
        public String getTextFont() {
            return switch (p.kind()) {
                case CURRENT -> "size:14;weight:bold;color:#1F2A33";
                case PLANNED, CHOICE -> "size:14;color:#546E7A";
                default -> "size:14;color:#1F2A33";
            };
        }
    }

    public static class CommentRow implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Comment c;
        CommentRow(Comment c) { this.c = c; }
        public String getHead() { return TaskDetailUI.nvl(c.author()) + " · " + TaskDetailUI.ts(c.createdAt()); }
        public String getText() { return c.text(); }
    }

    public class AttachmentRow implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Attachment a;
        AttachmentRow(Attachment a) { this.a = a; }
        public String getName() { return a.fileName(); }
        public String getInfo() {
            String origin = a.id().startsWith("D:") ? "SAP" : "eWF";
            String size = TaskDetailUI.size(a.sizeBytes());
            return size.isEmpty() ? origin : origin + " · " + size;
        }
        public void onDownload(ActionEvent ae) {
            try {
                download(svc().attachmentContent(uid(), m_processId, a.id()));
            } catch (ServiceException e) {
                Statusbar.outputError(e.getMessage());
            }
        }
    }

    public class SheetRow implements Serializable {
        private static final long serialVersionUID = 1L;
        private final SheetView v;
        SheetRow(SheetView v) { this.v = v; }
        public String getLabel() { return "PDF: " + v.label(); }
        public void onOpen(ActionEvent ae) { openSheet(v.code()); }
    }

    public class OptionRow implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Option o;
        OptionRow(Option o) { this.o = o; }
        public String getLabel() { return o.label(); }
        private boolean selected() { return o.code().equals(m_option); }
        public String getMark() { return selected() ? "◉" : "○"; }
        public String getBg() { return selected() ? "#E3F0FB" : "#FFFFFF"; }
        public String getBorder() { return selected() ? "#1565C0" : "#DDE3E8"; }
        public void onPick(ActionEvent ae) { m_option = o.code(); }
    }
}
