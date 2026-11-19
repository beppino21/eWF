package it.eone.ewf.ewfserver.view;

import it.eone.ewf.ewfserver.app.EwfApp;
import it.eone.ewf.ewfserver.context.EwfSession;
import it.eone.ewf.ewfserver.context.SessionGuard;
import it.eone.ewf.service.EwfService;
import it.eone.ewf.service.ServiceException;
import it.eone.ewf.service.Views.Attachment;
import it.eone.ewf.service.Views.Comment;
import it.eone.ewf.service.Views.FileContent;
import it.eone.ewf.service.Views.HistoryEntry;
import it.eone.ewf.service.Views.ProcessDetail;
import it.eone.ewf.service.Views.TimelineBar;
import org.eclnt.editor.annotations.CCGenClass;
import org.eclnt.jsfserver.base.faces.event.ActionEvent;
import org.eclnt.jsfserver.defaultscreens.Statusbar;
import org.eclnt.jsfserver.defaultscreens.YESNOPopup;
import org.eclnt.jsfserver.elements.componentnodes.COLDISTANCENode;
import org.eclnt.jsfserver.elements.componentnodes.LABELNode;
import org.eclnt.jsfserver.elements.componentnodes.ROWDISTANCENode;
import org.eclnt.jsfserver.elements.componentnodes.ROWNode;
import org.eclnt.jsfserver.elements.componentnodes.SCHEDULEITEMAREANode;
import org.eclnt.jsfserver.elements.componentnodes.SCHEDULEITEMNode;
import org.eclnt.jsfserver.elements.componentnodes.SCHEDULENode;
import org.eclnt.jsfserver.elements.events.BaseActionEventUpload;
import org.eclnt.jsfserver.elements.impl.DYNAMICCONTENTBinding;
import org.eclnt.jsfserver.elements.impl.FIXGRIDItem;
import org.eclnt.jsfserver.elements.impl.FIXGRIDListBinding;
import org.eclnt.jsfserver.elements.util.Trigger;
import org.eclnt.jsfserver.pagebean.PageBean;
import org.eclnt.jsfserver.util.tempfile.TempFileManager;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finestra sopra la lista con Storico, Gantt, Commenti e Allegati di un processo, senza passare dal dettaglio.
 * Le regole sono quelle del dettaglio (le verifica il servizio). readOnly: niente aggiunte ne' cancellazioni
 * (per i processi conclusi o consultati da "Processi a cui ho partecipato").
 */
@CCGenClass(expressionBase = "#{d.ProcessInfoUI}")
public class ProcessInfoUI extends PageBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DAY_HOUR = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final int SCALE = 1000;

    public enum Mode { HISTORY, GANTT, COMMENTS, ATTACHMENTS }

    private long m_processId;
    private boolean m_readOnly;
    private Mode m_mode = Mode.HISTORY;
    private String m_title = "";

    private final FIXGRIDListBinding<TaskDetailUI.HistoryItem> m_history = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<TaskDetailUI.CommentItem> m_comments = new FIXGRIDListBinding<>();
    private final FIXGRIDListBinding<AttachmentItem> m_attachments = new FIXGRIDListBinding<>();
    private final DYNAMICCONTENTBinding m_gantt = new DYNAMICCONTENTBinding();
    private String m_attachmentsMessage;
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
        try {
            for (Attachment a : svc().attachments(uid(), m_processId)) m_attachments.getItems().add(new AttachmentItem(a));
        } catch (ServiceException e) {
            m_attachmentsMessage = e.getMessage();
        }
    }

    // ------------------------------------------------------------------ Gantt

    /**
     * Una riga per persona (o team) nell'ordine in cui entra nel processo; ogni task e' una barra dall'arrivo
     * alla chiusura (o ad adesso, se e' ancora aperto). Scala orizzontale 0..1000 dal momento dell'avvio.
     */
    private void buildGantt() {
        List<TimelineBar> bars;
        try {
            bars = svc().timeline(uid(), m_processId);
        } catch (ServiceException e) {
            m_gantt.setContentNodes(new ArrayList<>());
            return;
        }
        Instant now = Instant.now();
        Instant start = bars.stream().map(TimelineBar::from).filter(x -> x != null).min(Instant::compareTo).orElse(now);
        Instant end = bars.stream().map(b -> b.to() == null ? now : b.to()).max(Instant::compareTo).orElse(now);
        long span = Math.max(Duration.between(start, end).toMillis(), 60_000);      // almeno un minuto
        ZoneId zone = EwfApp.get().zone();
        DateTimeFormatter fmt = span > Duration.ofDays(3).toMillis() ? DAY : DAY_HOUR;

        Map<String, List<TimelineBar>> rows = new LinkedHashMap<>();
        for (TimelineBar b : bars) rows.computeIfAbsent(b.who(), k -> new ArrayList<>()).add(b);

        // il contenuto dinamico sta in una t:rowdynamiccontent: e' fatto di righe
        List<org.eclnt.jsfserver.elements.impl.ROWDYNAMICCONTENTBinding.ComponentNode> content = new ArrayList<>();

        // scala dei tempi
        ROWNode scaleRow = new ROWNode();
        scaleRow.addSubNode(new LABELNode().setText("").setWidth("200"));
        SCHEDULENode scale = new SCHEDULENode();
        scale.setSchedulemax(SCALE).setWidth("100%").setHeight("22");
        for (int i = 0; i <= 4; i++) {
            Instant t = start.plusMillis(span * i / 4);
            int left = Math.min(i * SCALE / 4, SCALE - 120);
            SCHEDULEITEMAREANode a = new SCHEDULEITEMAREANode();
            a.setScheduleleft(left).setSchedulewidth(120);
            a.addSubNode(new ROWNode().addSubNode(new LABELNode().setText(fmt.format(t.atZone(zone)))
                    .setFont("size:10").setForeground("#666666")));
            scale.addSubNode(a);
        }
        scaleRow.addSubNode(scale);
        content.add(scaleRow);

        for (Map.Entry<String, List<TimelineBar>> r : rows.entrySet()) {
            ROWNode row = new ROWNode();
            row.addSubNode(new LABELNode().setText(r.getKey()).setFont("weight:bold").setWidth("200"));
            SCHEDULENode s = new SCHEDULENode();
            s.setSchedulemax(SCALE).setWidth("100%").setHeight("30").setBackground("#F5F5F5");
            for (TimelineBar b : r.getValue()) {
                Instant to = b.to() == null ? now : b.to();
                int left = (int) (Duration.between(start, b.from()).toMillis() * SCALE / span);
                int width = Math.max((int) (Duration.between(b.from(), to).toMillis() * SCALE / span), 8);
                left = Math.min(left, SCALE - width);
                SCHEDULEITEMNode item = new SCHEDULEITEMNode();
                item.setScheduleleft(left).setSchedulewidth(width)
                        .setBackground(barColor(b.status())).setText(width >= 60 ? barText(b) : "")
                        .setFont("size:10").setTooltip(tooltip(b, zone));
                s.addSubNode(item);
            }
            row.addSubNode(s);
            content.add(new ROWDISTANCENode().setHeight("4"));
            content.add(row);
        }

        // legenda
        content.add(new ROWDISTANCENode().setHeight("10"));
        ROWNode legend = new ROWNode();
        legend.addSubNode(new COLDISTANCENode().setWidth("200"));
        legend(legend, "Avvio", "#90A4AE");
        legend(legend, "Approvato", "#81C784");
        legend(legend, "Respinto", "#E57373");
        legend(legend, "Spostato", "#90CAF9");
        legend(legend, "In corso", "#FFD54F");
        legend(legend, "Annullato / non necessario", "#E0E0E0");
        content.add(legend);
        m_gantt.setContentNodes(content);
    }

    private static void legend(ROWNode row, String text, String color) {
        row.addSubNode(new LABELNode().setText(text).setBackground(color).setPadding("3").setFont("size:10"));
        row.addSubNode(new COLDISTANCENode().setWidth("6"));
    }

    static String barColor(String status) {
        return switch (status == null ? "" : status) {
            case "START" -> "#90A4AE";
            case "OK" -> "#81C784";
            case "KO" -> "#E57373";
            case "MOVED" -> "#90CAF9";
            case "OPEN", "EXECUTING" -> "#FFD54F";
            case "ERROR" -> "#FF8A65";
            default -> "#E0E0E0";
        };
    }

    private static String barText(TimelineBar b) {
        return "START".equals(b.status()) ? "Avvio" : b.taskText();
    }

    private static String tooltip(TimelineBar b, ZoneId zone) {
        StringBuilder t = new StringBuilder(b.taskText());
        t.append(" - ").append(statusText(b));
        t.append("\nDal ").append(DAY_HOUR.format(b.from().atZone(zone)));
        if (!"START".equals(b.status()))
            t.append(b.to() == null ? " (ancora aperto)" : " al " + DAY_HOUR.format(b.to().atZone(zone)));
        if (b.forWhom() != null) t.append("\nPer conto di ").append(b.forWhom());
        if (b.note() != null && !b.note().isBlank()) t.append("\nNota: ").append(b.note());
        return t.toString();
    }

    private static String statusText(TimelineBar b) {
        if (b.option() != null) return b.option();
        return switch (b.status() == null ? "" : b.status()) {
            case "START" -> "avviato";
            case "OK" -> "approvato";
            case "KO" -> "respinto";
            case "MOVED" -> "spostato";
            case "OPEN", "EXECUTING" -> "in corso";
            case "CANCELLED" -> "annullato";
            case "SKIPPED" -> "non necessario";
            case "ERROR" -> "errore";
            default -> b.status();
        };
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
    public boolean isUploadRow() { return isAttachmentsMode() && !m_readOnly; }
    public boolean isAttachmentsMessageRow() { return isAttachmentsMode() && m_attachmentsMessage != null; }
    private String tabBg(Mode m) { return m_mode == m ? "#BBDEFB" : null; }
    public String getHistoryBg() { return tabBg(Mode.HISTORY); }
    public String getGanttBg() { return tabBg(Mode.GANTT); }
    public String getCommentsBg() { return tabBg(Mode.COMMENTS); }
    public String getAttachmentsBg() { return tabBg(Mode.ATTACHMENTS); }
    public String getHistoryText() { return "Storico (" + m_history.getItems().size() + ")"; }
    public String getCommentsText() { return "Commenti (" + m_comments.getItems().size() + ")"; }
    public String getAttachmentsText() {
        return m_attachmentsMessage != null ? "Allegati (!)" : "Allegati (" + m_attachments.getItems().size() + ")";
    }
    public FIXGRIDListBinding<TaskDetailUI.HistoryItem> getHistory() { return m_history; }
    public FIXGRIDListBinding<TaskDetailUI.CommentItem> getComments() { return m_comments; }
    public FIXGRIDListBinding<AttachmentItem> getAttachments() { return m_attachments; }
    public DYNAMICCONTENTBinding getGantt() { return m_gantt; }
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
