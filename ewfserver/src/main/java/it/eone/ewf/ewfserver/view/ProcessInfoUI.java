package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.Attachment;
import it.eone.ewf.service.Views.AttachmentList;
import it.eone.ewf.service.Views.Comment;
import it.eone.ewf.service.Views.FileContent;
import it.eone.ewf.service.Views.HistoryEntry;
import it.eone.ewf.service.Views.ProcessDetail;
import it.eone.ewf.service.Views.TimelineBar;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.events.BaseActionEventUpload;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.Trigger;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.jsfserver.util.tempfile.TempFileManager;

import java.io.Serializable;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Finestra sopra la lista con Storico, Gantt, Commenti e Allegati di un processo, senza passare dal dettaglio.
 * Le regole sono quelle del dettaglio (le verifica il servizio). readOnly: niente aggiunte ne' cancellazioni
 * (per i processi conclusi o consultati da "Processi a cui ho partecipato").
 */
@CCGenClass(expressionBase = "#{d.ProcessInfoUI}")
public class ProcessInfoUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Mode { HISTORY, GANTT, COMMENTS, ATTACHMENTS }

    private long m_processId;
    private boolean m_readOnly;
    private boolean m_canUpload;
    private Mode m_mode = Mode.HISTORY;
    private String m_title = "";

    private final FIXGRIDListBinding<TaskDetailUI.HistoryItem> m_history = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<TaskDetailUI.CommentItem> m_comments = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<AttachmentItem> m_attachments = new FIXGRIDListBinding<>();
    private String m_ganttSvg;
    private int m_ganttHeight = 60;
    private String m_attachmentsMessage;
    private boolean m_attachmentsFailed;
    private boolean m_originVisible;
    private String m_newComment;
    private final Trigger m_downloadTrigger = new Trigger();
    private String m_downloadUrl;

    public ProcessInfoUI() {
        SessionGuard.requireUser("/ProcessInfo.xml");
    }

    @Override public String getPageName() { return "/ProcessInfo.xml"; }
    @Override public String getRootExpressionUsedInPage() { return "#{d.ProcessInfoUI}"; }

    public void prepare(long processId, Mode mode, boolean readOnly) {
        m_processId = processId;
        m_readOnly = readOnly;
        m_mode = mode;
        try {
            m_canUpload = !readOnly && svc().canAddAttachments(uid(), processId);
        } catch (ServiceException e) {
            m_canUpload = false;
        }
        loadDetail();
        loadAttachments();
        buildGantt();
    }

    private static EwfService svc() { return EwfApp.get().service(); }
    private static long uid() { return EwfSession.instance().userId(); }

    private void loadDetail() {
        m_history.getItems().clear();
        m_comments.getItems().clear();
        try {
            ProcessDetail d = svc().detail(uid(), m_processId);
            m_originVisible = "BOTH".equals(d.header().attachmentScope());
            m_title = d.header().processTypeText() + " - " + d.header().docType() + " " + d.header().documentKey();
            for (HistoryEntry h : d.history()) m_history.getItems().add(new TaskDetailUI.HistoryItem(h));
            for (Comment c : d.comments()) m_comments.getItems().add(new TaskDetailUI.CommentItem(c));
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    private void loadAttachments() {
        m_attachments.getItems().clear();
        m_attachmentsMessage = null;
        m_attachmentsFailed = false;
        try {
            AttachmentList list = svc().attachmentList(uid(), m_processId);
            for (Attachment a : list.items()) m_attachments.getItems().add(new AttachmentItem(a));
            m_attachmentsMessage = list.sourceWarning();               // con BOTH: SAP non risponde, eWF si'
        } catch (ServiceException e) {
            m_attachmentsFailed = true;
            m_attachmentsMessage = e.getMessage();
        }
    }

    // ------------------------------------------------------------------ Gantt

    /** Gantt disegnato come SVG (vedi GanttSvg): scala compressa, etichette dove c'e' spazio. */
    private void buildGantt() {
        try {
            GanttSvg.Result r = GanttSvg.render(svc().timeline(uid(), m_processId), Instant.now(), EwfApp.get().zone(), 960);
            m_ganttSvg = r.svg();
            m_ganttHeight = r.height();
        } catch (ServiceException e) {
            m_ganttSvg = null;
            m_ganttHeight = 60;
            Statusbar.outputError(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ azioni

    public void onShowHistory(ActionEvent ae) { m_mode = Mode.HISTORY; }
    public void onShowGantt(ActionEvent ae) { m_mode = Mode.GANTT; buildGantt(); }
    public void onShowComments(ActionEvent ae) { m_mode = Mode.COMMENTS; }
    public void onShowAttachments(ActionEvent ae) { m_mode = Mode.ATTACHMENTS; }

    public void onAddComment(ActionEvent ae) {
        if (m_readOnly) return;
        try {
            svc().addComment(uid(), m_processId, m_newComment);
            m_newComment = null;
            loadDetail();
            Statusbar.outputSuccess("Commento aggiunto");
        } catch (ServiceException e) {
            Statusbar.outputError(e.getMessage());
        }
    }

    public void onUpload(ActionEvent ae) {
        if (m_readOnly || !(ae instanceof BaseActionEventUpload up)) return;
        int added = 0;
        for (int i = 0; i < up.getNumberOfUploadedFiles(); i++) {
            String name = up.getClientFileName(i);
            String hex = up.getHexByteString(i);
            if (hex == null || hex.isEmpty()) {
                Statusbar.outputWarning("File vuoto ignorato: " + name);
                continue;
            }
            try {
                svc().addAttachment(uid(), m_processId, name, TaskDetailUI.mimeOf(name), HexFormat.of().parseHex(hex));
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

    // ------------------------------------------------------------------ binding

    public String getTitle() { return m_title; }
    public boolean isHistoryMode() { return m_mode == Mode.HISTORY; }
    public boolean isGanttMode() { return m_mode == Mode.GANTT; }
    public boolean isCommentsMode() { return m_mode == Mode.COMMENTS; }
    public boolean isAttachmentsMode() { return m_mode == Mode.ATTACHMENTS; }
    public boolean isEditable() { return !m_readOnly; }
    public boolean isCommentsEditRow() { return isCommentsMode() && !m_readOnly; }
    public boolean isUploadRow() { return isAttachmentsMode() && !m_readOnly && m_canUpload; }
    public boolean isAttachmentsMessageRow() { return isAttachmentsMode() && m_attachmentsMessage != null; }
    private String tabBg(Mode m) { return m_mode == m ? "#BBDEFB" : null; }
    public String getHistoryBg() { return tabBg(Mode.HISTORY); }
    public String getGanttBg() { return tabBg(Mode.GANTT); }
    public String getCommentsBg() { return tabBg(Mode.COMMENTS); }
    public String getAttachmentsBg() { return tabBg(Mode.ATTACHMENTS); }
    public String getHistoryText() { return "Storico (" + m_history.getItems().size() + ")"; }
    public String getCommentsText() { return "Commenti (" + m_comments.getItems().size() + ")"; }
    public String getAttachmentsText() {
        return m_attachmentsFailed ? "Allegati (!)" : "Allegati (" + m_attachments.getItems().size() + ")";
    }
    public FIXGRIDListBinding<TaskDetailUI.HistoryItem> getHistory() { return m_history; }
    public FIXGRIDListBinding<TaskDetailUI.CommentItem> getComments() { return m_comments; }
    public FIXGRIDListBinding<AttachmentItem> getAttachments() { return m_attachments; }
    public String getGanttSvg() { return m_ganttSvg; }
    public String getGanttHeight() { return String.valueOf(m_ganttHeight); }
    public boolean isOriginVisible() { return m_originVisible; }

    public boolean isAttachmentsMessageVisible() { return m_attachmentsMessage != null; }
    public String getAttachmentsMessage() { return m_attachmentsMessage; }
    public String getNewComment() { return m_newComment; }
    public void setNewComment(String v) { m_newComment = v; }
    public Trigger getDownloadTrigger() { return m_downloadTrigger; }
    public String getDownloadUrl() { return m_downloadUrl; }

    // ------------------------------------------------------------------ allegati

    public class AttachmentItem extends FIXGRIDItem implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Attachment a;
        AttachmentItem(Attachment a) { this.a = a; }
        public String getName() { return a.fileName(); }
        public String getOrigin() { return a.id().startsWith("D:") ? "SAP" : "eWF"; }
        public String getSize() { return TaskDetailUI.size(a.sizeBytes()); }
        public String getBy() { return TaskDetailUI.nvl(a.uploadedBy()); }
        public String getWhen() { return TaskDetailUI.ts(a.uploadedAt()); }
        public boolean isDeletable() { return !m_readOnly && a.deletable(); }

        public void onDownload(ActionEvent ae) {
            try {
                download(svc().attachmentContent(uid(), m_processId, a.id()));
            } catch (ServiceException e) {
                Statusbar.outputError(e.getMessage());
            }
        }

        public void onDelete(ActionEvent ae) {
            if (!isDeletable()) return;
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
}
