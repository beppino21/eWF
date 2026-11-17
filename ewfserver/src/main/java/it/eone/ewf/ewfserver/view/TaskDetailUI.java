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
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.events.BaseActionEventUpload;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.Trigger;
import org.eclnt.jsfserver.elements.util.ValidValuesBinding;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.jsfserver.util.tempfile.TempFileManager;

import java.io.Serializable;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;

/**
 * Dettaglio di un processo: dati del documento, azioni sul proprio task (approva, respingi o scelta,
 * sposta, funzioni speciali), schede PDF e collegamento SAP GUI, percorso, storico, commenti, allegati.
 * Ogni controllo di visibilita' e autorizzazione lo fa il livello di servizio.
 */
@CCGenClass(expressionBase = "#{d.TaskDetailUI}")
public class TaskDetailUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public interface IListener extends Serializable {
        /** Torna alla lista (anche dopo un'azione che ha chiuso il task). */
        void reactOnBack();
        void reactOnLogout();
    }

    private IListener m_listener;
    private long m_processId;
    private ProcessDetail m_detail;

    private final FIXGRIDListBinding<FieldItem> m_header = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<PathItem> m_path = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<HistoryItem> m_history = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<CommentItem> m_comments = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<AttachmentItem> m_attachments = new FIXGRIDListBinding<>();
    private final ValidValuesBinding m_optionsVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_usersVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_functionsVVB = new ValidValuesBinding();
    private final ValidValuesBinding m_sheetsVVB = new ValidValuesBinding();

    private String m_note;
    private String m_option;
    private String m_moveTo;
    private String m_function;
    private String m_sheet;
    private String m_newComment;
    private String m_attachmentsMessage;

    private final Trigger m_downloadTrigger = new Trigger();
    private String m_downloadUrl;

    public TaskDetailUI() {
        SessionGuard.requireUser("/TaskDetail.xml");
    }

    @Override public String getPageName() { return "/TaskDetail.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.TaskDetailUI}"; }

    public void prepare(long processId, IListener listener) {
        m_processId = processId;
        m_listener = listener;
        load();
    }

    private EwfService svc() { return EwfApp.get().service(); }
    private long uid() { return EwfSession.instance().userId(); }

    private void load() {
        try {
            m_detail = svc().detail(uid(), m_processId);
        } catch (ServiceException e) {
            m_detail = null;
            Statusbar.outputError(e.getMessage());
            return;
        }
        Header h = m_detail.header();
        m_header.getItems().clear();
        addField("Processo", h.processTypeText());
        addField("Documento", h.docType() + " " + h.documentKey());
        addField("Stato", statusText(h.status()));
        if (h.value() != null) addField("Valore", EwfService.formatAmount(h.value().amount()) + " " + h.value().currency());
        addField("Avviato da", h.starter());
        addField("Avviato il", ts(h.startedAt()));
        if (h.text() != null) addField("Descrizione", h.text());
        for (Map.Entry<String, String> a : h.attributes().entrySet())
            if (a.getKey().startsWith("sap.")) addField(a.getKey().substring(4), a.getValue());

        m_path.getItems().clear();
        for (PathEntry p : m_detail.path()) m_path.getItems().add(new PathItem(p));
        m_history.getItems().clear();
        for (HistoryEntry e : m_detail.history()) m_history.getItems().add(new HistoryItem(e));
        m_comments.getItems().clear();
        for (Comment c : m_detail.comments()) m_comments.getItems().add(new CommentItem(c));

        MyTask t = m_detail.myTask();
        m_optionsVVB.clear();
        m_functionsVVB.clear();
        m_option = null;
        m_function = null;
        if (t != null) {
            for (Option o : t.options()) m_optionsVVB.addValidValue(o.code(), o.label());
            for (Function f : t.functions()) m_functionsVVB.addValidValue(f.code(), f.label());
        }
        m_usersVVB.clear();
        m_moveTo = null;
        if (t != null && t.webActionable())
            for (UserChoice u : svc().findUsers("", 300))
                if (u.userId() != uid()) m_usersVVB.addValidValue(String.valueOf(u.userId()), u.displayName());
        m_sheetsVVB.clear();
        for (SheetView v : m_detail.links().sheets()) m_sheetsVVB.addValidValue(v.code(), v.label());
        m_sheet = m_detail.links().sheets().isEmpty() ? null : m_detail.links().sheets().get(0).code();
        loadAttachments();
    }

    private void loadAttachments() {
        m_attachments.getItems().clear();
        m_attachmentsMessage = null;
        try {
            for (Attachment a : svc().attachments(uid(), m_processId)) m_attachments.getItems().add(new AttachmentItem(a));
        } catch (ServiceException e) {
            m_attachmentsMessage = e.getMessage();                    // es. sistema di origine non raggiungibile
        }
    }

    private void addField(String label, String value) {
        if (value != null && !value.isBlank()) m_header.getItems().add(new FieldItem(label, value));
    }

    // ------------------------------------------------------------------ azioni sul task

    public void onApprove(ActionEvent ae) {
        act(() -> svc().approve(uid(), myTask().taskId(), m_note, myTask().rowVersion()), "Approvato");
    }

    public void onReject(ActionEvent ae) {
        act(() -> svc().reject(uid(), myTask().taskId(), m_note, myTask().rowVersion()), "Respinto");
    }

    public void onChoose(ActionEvent ae) {
        if (m_option == null) {
            Statusbar.outputError("Scegliere come proseguire");
            return;
        }
        act(() -> svc().choose(uid(), myTask().taskId(), m_option, m_note, myTask().rowVersion()), "Scelta registrata");
    }

    public void onMove(ActionEvent ae) {
        if (m_moveTo == null || m_moveTo.isBlank()) {
            Statusbar.outputError("Scegliere a chi spostare l'attivita'");
            return;
        }
        long to = Long.parseLong(m_moveTo);
        act(() -> svc().move(uid(), myTask().taskId(), to, m_note), "Attivita' spostata");
    }

    public void onRunFunction(ActionEvent ae) {
        MyTask t = myTask();
        if (t == null || m_function == null) {
            Statusbar.outputError("Scegliere la funzione da eseguire");
            return;
        }
        Function f = t.functions().stream().filter(x -> x.code().equals(m_function)).findFirst().orElse(null);
        if (f == null) return;
        Runnable run = () -> {
            try {
                FunctionResult r = svc().runFunction(uid(), t.taskId(), f.code());
                if (r.ok()) Statusbar.outputSuccess(r.message()); else Statusbar.outputWarning(r.message());
                load();
            } catch (ServiceException e) {
                Statusbar.outputError(e.getMessage());
            }
        };
        if (f.confirmText() == null || f.confirmText().isBlank()) {
            run.run();
            return;
        }
        YESNOPopup.createInstance(f.label(), f.confirmText(), new YESNOPopup.IYesNoListener() {
            @Override public void reactOnYes() { run.run(); }
            @Override public void reactOnNo() { }
        });
    }

    private interface Action { Object run(); }

    /** Azione che chiude o sposta il task: in caso di successo si torna alla lista. */
    private void act(Action a, String okMessage) {
        if (myTask() == null) return;
        try {
            Object r = a.run();
            // Il motore non lancia eccezioni quando un'azione in SAP (handler) fallisce: restituisce
            // un esito REJECTED (task riaperto) o UNKNOWN (processo in errore). Va mostrato all'utente.
            if (r instanceof Requests.Outcome o && o.result() != Requests.Outcome.Result.DONE) {
                String msg = o.message() == null ? "" : o.message();
                if (o.result() == Requests.Outcome.Result.REJECTED)
                    Statusbar.outputError("Operazione non eseguita: " + msg);
                else
                    Statusbar.outputError("Esito incerto in SAP, processo in errore (da verificare): " + msg);
                load();
                return;
            }
            m_note = null;
            Statusbar.outputSuccess(okMessage);
            if (m_listener != null) m_listener.reactOnBack();
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
            if (e.code() == ServiceException.Code.INVALID_STATE) load();   // un collega e' arrivato prima
        }
    }

    private MyTask myTask() { return m_detail == null ? null : m_detail.myTask(); }

    // ------------------------------------------------------------------ documenti, commenti, allegati

    public void onOpenSheet(ActionEvent ae) {
        if (m_sheet == null) return;
        try {
            download(svc().documentSheet(uid(), m_processId, m_sheet));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            // dettagli solo nel log di Tomcat
            System.err.println("[TaskDetailUI] Errore scheda " + m_sheet + " processo " + m_processId + ": " + e);
            e.printStackTrace();
            Statusbar.outputError("Impossibile generare la scheda: vedere il log del server.");
        }
    }

    public void onOpenSap(ActionEvent ae) {
        try {
            download(svc().sapGuiShortcut(uid(), m_processId));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onAddComment(ActionEvent ae) {
        try {
            svc().addComment(uid(), m_processId, m_newComment);
            m_newComment = null;
            load();
            Statusbar.outputSuccess("Commento aggiunto");
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onUpload(ActionEvent ae) {
        if (!(ae instanceof BaseActionEventUpload up)) return;
        int added = 0;
        for (int i = 0; i < up.getNumberOfUploadedFiles(); i++) {
            String name = up.getClientFileName(i);
            String hex = up.getHexByteString(i);
            if (hex == null || hex.isEmpty()) {
                Statusbar.outputWarning("File vuoto ignorato: " + name);
                continue;
            }
            try {
                svc().addAttachment(uid(), m_processId, name, mimeOf(name), HexFormat.of().parseHex(hex));
                added++;
            } catch (ServiceException e) {
                Statusbar.outputError(name + ": " + e.getMessage());
            }
        }
        loadAttachments();
        if (added > 0) Statusbar.outputSuccess(added == 1 ? "Allegato aggiunto" : added + " allegati aggiunti");
    }

    private void download(FileContent f) {
        m_downloadUrl = TempFileManager.saveTempFile(f.fileName(), f.mimeType(), f.content());
        m_downloadTrigger.trigger();
    }

    static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".msg")) return "application/vnd.ms-outlook";
        if (n.endsWith(".eml")) return "message/rfc822";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ navigazione

    public void onBack(ActionEvent ae) { if (m_listener != null) m_listener.reactOnBack(); }
    public void onRefresh(ActionEvent ae) { load(); }
    public void onLogout(ActionEvent ae) { if (m_listener != null) m_listener.reactOnLogout(); }

    // ------------------------------------------------------------------ binding

    public String getTitle() {
        if (m_detail == null) return "Dettaglio";
        Header h = m_detail.header();
        return h.processTypeText() + " - " + h.documentKey();
    }

    public boolean isLoaded() { return m_detail != null; }
    public boolean isCanAct() { return myTask() != null && myTask().webActionable(); }
    public boolean isSapGuiOnly() { return myTask() != null && !myTask().webActionable(); }
    public boolean isHasOptions() { return isCanAct() && !myTask().options().isEmpty(); }
    public boolean isPlainDecision() { return isCanAct() && myTask().options().isEmpty(); }
    public boolean isHasFunctions() { return isCanAct() && !myTask().functions().isEmpty(); }
    public boolean isSapGui() { return m_detail != null && m_detail.links().sapGui(); }
    public boolean isAttachmentsMessageVisible() { return m_attachmentsMessage != null; }

    /** I pulsanti delle schede stanno in "La tua attivita'"; nel riquadro Documento solo se non c'e' un'attivita'. */
    public boolean isSheetsInDocument() { return !isCanAct(); }

    // Titoli dei riquadri con il numero di elementi, cosi' si vede se vale la pena aprirli anche quando sono chiusi.
    public String getPathTitle() { return paneTitle("Percorso", m_path.getItems().size()); }
    public String getHistoryTitle() { return paneTitle("Storico", m_history.getItems().size()); }
    public String getCommentsTitle() { return paneTitle("Commenti", m_comments.getItems().size()); }
    public String getAttachmentsTitle() {
        return m_attachmentsMessage != null ? "Allegati \u2014 non disponibili" : paneTitle("Allegati", m_attachments.getItems().size());
    }
    public String getPathColor() { return paneColor(m_path.getItems().size()); }
    public String getHistoryColor() { return paneColor(m_history.getItems().size()); }
    public String getCommentsColor() { return paneColor(m_comments.getItems().size()); }
    public String getAttachmentsColor() { return m_attachmentsMessage != null ? "#B00020" : paneColor(m_attachments.getItems().size()); }

    private static String paneTitle(String name, int n) { return n == 0 ? name + " \u2014 nessuno" : name + " (" + n + ")"; }
    /** Grigio se vuoto, colore normale del tema altrimenti. */
    private static String paneColor(int n) { return n == 0 ? "#9E9E9E" : null; }

    public String getTaskLabel() {
        MyTask t = myTask();
        if (t == null) return "";
        String who = t.reason() == Reason.SUBSTITUTE ? " - per conto di " + t.forWhom()
                : t.reason() == Reason.TEAM ? " - attivita' del team " + t.forWhom() : "";
        return t.taskText() + who;
    }

    public FIXGRIDListBinding<FieldItem> getHeader() { return m_header; }
    public FIXGRIDListBinding<PathItem> getPath() { return m_path; }
    public FIXGRIDListBinding<HistoryItem> getHistory() { return m_history; }
    public FIXGRIDListBinding<CommentItem> getComments() { return m_comments; }
    public FIXGRIDListBinding<AttachmentItem> getAttachments() { return m_attachments; }
    public ValidValuesBinding getOptionsVVB() { return m_optionsVVB; }
    public ValidValuesBinding getUsersVVB() { return m_usersVVB; }
    public ValidValuesBinding getFunctionsVVB() { return m_functionsVVB; }
    public ValidValuesBinding getSheetsVVB() { return m_sheetsVVB; }

    public String getNote() { return m_note; }
    public void setNote(String v) { m_note = v; }
    public String getOption() { return m_option; }
    public void setOption(String v) { m_option = v; }
    public String getMoveTo() { return m_moveTo; }
    public void setMoveTo(String v) { m_moveTo = v; }
    public String getFunction() { return m_function; }
    public void setFunction(String v) { m_function = v; }
    public String getSheet() { return m_sheet; }
    public void setSheet(String v) { m_sheet = v; }
    public String getNewComment() { return m_newComment; }
    public void setNewComment(String v) { m_newComment = v; }
    public String getAttachmentsMessage() { return m_attachmentsMessage; }
    public Trigger getDownloadTrigger() { return m_downloadTrigger; }
    public String getDownloadUrl() { return m_downloadUrl; }

    // ------------------------------------------------------------------ righe delle griglie

    public static class FieldItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String label, value;
        FieldItem(String label, String value) { this.label = label; this.value = value; }
        public String getLabel() { return label; }
        public String getValue() { return value; }
    }

    public static class PathItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final PathEntry p;
        PathItem(PathEntry p) { this.p = p; }
        public String getState() {
            return switch (p.kind()) {
                case DONE -> "Fatto";
                case CURRENT -> "In corso";
                case PLANNED -> "Previsto";
                case CHOICE -> "Scelta";
            };
        }
        public String getStateColor() {
            return switch (p.kind()) {
                case DONE -> "#C8E6C9";
                case CURRENT -> "#FFE082";
                case PLANNED -> "#ECEFF1";
                case CHOICE -> "#BBDEFB";
            };
        }
        public String getStep() { return String.valueOf(p.stepNo()); }
        public String getTask() { return p.taskText(); }
        public String getWho() {
            return p.kind() == PathEntry.Kind.CHOICE ? String.join(" / ", p.options()) : nvl(p.who());
        }
        public String getNote() { return nvl(p.note()); }
    }

    public static class HistoryItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final HistoryEntry h;
        HistoryItem(HistoryEntry h) { this.h = h; }
        public String getWhen() { return ts(h.closedAt()); }
        public long getWhenSort() { return h.closedAt() == null ? 0 : h.closedAt().toEpochMilli(); }
        public String getTask() { return h.taskText(); }
        public String getOutcome() {
            String s = switch (nvl(h.status())) {
                case "OK" -> "Approvato";
                case "KO" -> "Respinto";
                case "MOVED" -> "Spostato";
                case "CANCELLED" -> "Annullato";
                case "SKIPPED" -> "Non necessario";
                case "ERROR" -> "Errore";
                default -> h.status();
            };
            if (h.option() != null) s = h.option();
            return h.movedTo() == null ? s : s + " a " + h.movedTo();
        }
        public String getWho() { return nvl(h.actedBy()) + (h.forWhom() == null ? "" : " per conto di " + h.forWhom()); }
        public String getNote() { return nvl(h.note()); }
    }

    public static class CommentItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Comment c;
        CommentItem(Comment c) { this.c = c; }
        public String getWhen() { return ts(c.createdAt()); }
        public String getAuthor() { return nvl(c.author()); }
        public String getText() { return c.text(); }
    }

    public class AttachmentItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Attachment a;
        AttachmentItem(Attachment a) { this.a = a; }
        public String getName() { return a.fileName(); }
        public String getSize() { return size(a.sizeBytes()); }
        public String getBy() { return nvl(a.uploadedBy()); }
        public String getWhen() { return ts(a.uploadedAt()); }
        public boolean isDeletable() { return a.deletable(); }

        public void onDownload(ActionEvent ae) {
            try {
                download(svc().attachmentContent(uid(), m_processId, a.id()));
            } catch (ServiceException e) {
                Statusbar.outputError(e.getMessage());
            }
        }

        public void onDelete(ActionEvent ae) {
            YESNOPopup.createInstance("Elimina allegato", "Eliminare " + a.fileName() + "?", new YESNOPopup.IYesNoListener() {
                @Override public void reactOnYes() {
                    try {
                        svc().deleteAttachment(uid(), m_processId, a.id());
                        loadAttachments();
                        Statusbar.outputSuccess("Allegato eliminato");
                    } catch (ServiceException e) {
                        Statusbar.outputError(e.getMessage());
                    }
                }
                @Override public void reactOnNo() { }
            });
        }
    }

    // ------------------------------------------------------------------ formati

    static String ts(Instant i) {
        return i == null ? "" : TS.format(i.atZone(EwfApp.get().zone()));
    }

    static String size(Long b) {
        if (b == null) return "";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.ITALY, "%.1f MB", b / (1024.0 * 1024.0));
    }

    static String statusText(String s) {
        return switch (nvl(s)) {
            case "RUNNING" -> "In approvazione";
            case "SUSPENDED" -> "Sospeso";
            case "COMPLETING", "COMPLETED" -> "Approvato";
            case "REJECTED" -> "Respinto";
            case "CANCELLED" -> "Annullato";
            case "ERROR" -> "In errore";
            default -> s;
        };
    }

    static String nvl(String s) { return s == null ? "" : s; }
}
