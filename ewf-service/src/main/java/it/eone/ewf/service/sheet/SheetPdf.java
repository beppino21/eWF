package it.eone.ewf.service.sheet;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.BaseFont;
import it.eone.ewf.service.sheet.DocumentSheet.*;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Impaginazione PDF di una {@link DocumentSheet} con OpenPDF: A4 verticale, titolo, riquadri etichetta/valore
 * (stile modulo: etichetta piccola in alto, valore sotto), tabelle con intestazione ripetuta, testi.
 * Pie' di pagina con riferimento, data di generazione e "Pag. n di m". Nessun logo ne' dato aziendale fisso.
 */
public final class SheetPdf {

    private static final Color GRAY = new Color(0x55, 0x55, 0x55);
    private static final Color LINE = new Color(0xB0, 0xB0, 0xB0);
    private static final Color HEAD_BG = new Color(0xE8, 0xEC, 0xF0);
    private static final Color ALT_BG = new Color(0xF7, 0xF8, 0xFA);
    private static final Color ACCENT = new Color(0x2B, 0x4C, 0x6F);
    private static final float BORDER = 0.5f;

    private static final Font F_TITLE = new Font(Font.HELVETICA, 15f, Font.BOLD, ACCENT);
    private static final Font F_SUBTITLE = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, GRAY);
    private static final Font F_SECTION = new Font(Font.HELVETICA, 9.5f, Font.BOLD, ACCENT);
    private static final Font F_LABEL = new Font(Font.HELVETICA, 6.5f, Font.NORMAL, GRAY);
    private static final Font F_VALUE = new Font(Font.HELVETICA, 9f, Font.NORMAL);
    private static final Font F_HEAD = new Font(Font.HELVETICA, 7.5f, Font.BOLD);
    private static final Font F_CELL = new Font(Font.HELVETICA, 8f, Font.NORMAL);
    private static final Font F_CELL_B = new Font(Font.HELVETICA, 8f, Font.BOLD);
    private static final Font F_NOTE = new Font(Font.HELVETICA, 7.5f, Font.ITALIC, GRAY);
    private static final Font F_TEXT = new Font(Font.HELVETICA, 8.5f, Font.NORMAL);
    private static final Font F_FOOT = new Font(Font.HELVETICA, 6.5f, Font.NORMAL, GRAY);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final ZoneId zone;

    public SheetPdf(ZoneId zone) {
        this.zone = zone;
    }

    public byte[] render(DocumentSheet sheet, Instant generatedAt) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4, 36, 36, 34, 46);
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            String foot = (sheet.reference() == null ? "" : sheet.reference() + "  -  ")
                    + "Generato da eWF il " + STAMP.format(generatedAt.atZone(zone));
            writer.setPageEvent(new Footer(foot));
            doc.addTitle(sheet.title());
            doc.addCreator("eWF");
            doc.open();

            Paragraph title = new Paragraph(nvl(sheet.title()), F_TITLE);
            title.setSpacingAfter(sheet.subtitle() == null ? 8 : 1);
            doc.add(title);
            if (sheet.subtitle() != null) {
                Paragraph sub = new Paragraph(sheet.subtitle(), F_SUBTITLE);
                sub.setSpacingAfter(8);
                doc.add(sub);
            }
            for (Section s : sheet.sections()) {
                if (s instanceof Fields f) fields(doc, f);
                else if (s instanceof Table t) table(doc, t);
                else if (s instanceof Text t) text(doc, t);
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Impaginazione PDF non riuscita: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ sezioni

    private static void fields(Document doc, Fields f) throws DocumentException {
        List<Field> visible = f.fields().stream().filter(x -> x.value() != null && !x.value().isBlank()).toList();
        if (visible.isEmpty()) return;
        sectionTitle(doc, f.title());
        PdfPTable t = new PdfPTable(f.columns());
        t.setWidthPercentage(100);
        t.setSpacingBefore(3);
        int used = 0;
        for (Field x : visible) {
            int span = Math.max(1, Math.min(x.span(), f.columns()));
            if (used + span > f.columns()) {                         // non entra nella riga: chiudo la riga
                fill(t, f.columns() - used);
                used = 0;
            }
            PdfPCell c = field(x.label(), x.value());
            c.setColspan(span);
            t.addCell(c);
            used = (used + span) % f.columns();
        }
        if (used > 0) fill(t, f.columns() - used);
        doc.add(t);
    }

    private static void fill(PdfPTable t, int n) {
        if (n <= 0) return;
        PdfPCell c = new PdfPCell();
        c.setColspan(n);
        c.setBorderWidth(BORDER);
        c.setBorderColor(LINE);
        t.addCell(c);
    }

    private static PdfPCell field(String label, String value) {
        PdfPCell c = new PdfPCell();
        c.setBorderWidth(BORDER);
        c.setBorderColor(LINE);
        c.setPadding(3);
        c.setPaddingBottom(5);
        Paragraph l = new Paragraph(nvl(label).toUpperCase(), F_LABEL);
        l.setLeading(8);
        c.addElement(l);
        Paragraph v = new Paragraph(nvl(value), F_VALUE);
        v.setLeading(11);
        c.addElement(v);
        return c;
    }

    private static void table(Document doc, Table t) throws DocumentException {
        if (t.rows().isEmpty() && t.emptyText() == null) return;
        sectionTitle(doc, t.title());
        if (t.rows().isEmpty()) {
            Paragraph p = new Paragraph(t.emptyText(), F_NOTE);
            p.setSpacingAfter(2);
            doc.add(p);
            return;
        }
        float[] widths = new float[t.columns().size()];
        for (int i = 0; i < widths.length; i++) widths[i] = t.columns().get(i).width();
        PdfPTable pt = new PdfPTable(widths);
        pt.setWidthPercentage(100);
        pt.setSpacingBefore(3);
        pt.setHeaderRows(1);
        for (Column col : t.columns()) {
            PdfPCell c = new PdfPCell(new Phrase(nvl(col.label()), F_HEAD));
            c.setBackgroundColor(HEAD_BG);
            c.setBorderWidth(BORDER);
            c.setBorderColor(LINE);
            c.setPadding(3.5f);
            c.setHorizontalAlignment(align(col.align()));
            pt.addCell(c);
        }
        int n = 0;
        for (Row r : t.rows()) {
            Color bg = n++ % 2 == 0 ? Color.WHITE : ALT_BG;
            for (int i = 0; i < widths.length; i++) {
                String v = i < r.cells().size() ? r.cells().get(i) : "";
                pt.addCell(cell(v, F_CELL, t.columns().get(i).align(), bg));
            }
            if (r.note() != null && !r.note().isBlank()) {
                PdfPCell note = new PdfPCell(new Phrase(r.note(), F_NOTE));
                note.setColspan(widths.length);
                note.setBackgroundColor(bg);
                note.setBorderWidth(BORDER);
                note.setBorderColor(LINE);
                note.setPadding(3);
                note.setPaddingLeft(12);
                pt.addCell(note);
            }
        }
        if (t.total() != null) {
            for (int i = 0; i < widths.length; i++) {
                String v = i < t.total().size() ? t.total().get(i) : "";
                PdfPCell c = cell(v, F_CELL_B, t.columns().get(i).align(), HEAD_BG);
                pt.addCell(c);
            }
        }
        doc.add(pt);
    }

    private static PdfPCell cell(String v, Font f, Align a, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(nvl(v), f));
        c.setBackgroundColor(bg);
        c.setBorderWidth(BORDER);
        c.setBorderColor(LINE);
        c.setPadding(3);
        c.setHorizontalAlignment(align(a));
        return c;
    }

    private static void text(Document doc, Text t) throws DocumentException {
        if (t.text() == null || t.text().isBlank()) return;
        sectionTitle(doc, t.title());
        PdfPTable box = new PdfPTable(1);
        box.setWidthPercentage(100);
        box.setSpacingBefore(3);
        PdfPCell c = new PdfPCell();
        c.setBorderWidth(BORDER);
        c.setBorderColor(LINE);
        c.setPadding(5);
        for (String line : t.text().strip().split("\\R")) {
            Paragraph p = new Paragraph(line.isEmpty() ? " " : line, F_TEXT);
            p.setLeading(11);
            c.addElement(p);
        }
        box.addCell(c);
        doc.add(box);
    }

    private static void sectionTitle(Document doc, String title) throws DocumentException {
        if (title == null || title.isBlank()) {
            doc.add(spacer(4));
            return;
        }
        Paragraph p = new Paragraph(title, F_SECTION);
        p.setLeading(11);
        p.setSpacingBefore(7);
        doc.add(p);
    }

    private static Paragraph spacer(float h) {
        Paragraph p = new Paragraph(" ", F_FOOT);
        p.setLeading(h);
        return p;
    }

    private static int align(Align a) {
        return switch (a) {
            case LEFT -> Element.ALIGN_LEFT;
            case CENTER -> Element.ALIGN_CENTER;
            case RIGHT -> Element.ALIGN_RIGHT;
        };
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** Pie' di pagina: riferimento a sinistra, "Pag. n di m" a destra (m scritto a fine documento). */
    private static final class Footer extends PdfPageEventHelper {
        private final String text;
        private PdfTemplate total;
        private BaseFont bf;

        Footer(String text) {
            this.text = text;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(40, 10);
            bf = F_FOOT.getCalculatedBaseFont(false);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float y = document.bottom() - 18;
            cb.setLineWidth(0.4f);
            cb.setColorStroke(LINE);
            cb.moveTo(document.left(), y + 9);
            cb.lineTo(document.right(), y + 9);
            cb.stroke();
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(text, F_FOOT), document.left(), y, 0);
            String page = "Pag. " + writer.getPageNumber() + " di ";
            float w = bf.getWidthPoint(page, F_FOOT.getSize());
            float x = document.right() - w - bf.getWidthPoint("000", F_FOOT.getSize());
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT, new Phrase(page, F_FOOT), x, y, 0);
            cb.addTemplate(total, x + w, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(bf, F_FOOT.getSize());
            total.setColorFill(GRAY);
            total.setTextMatrix(0, 0);
            total.showText(String.valueOf(writer.getPageNumber() - 1));   // alla chiusura e' gia' avanzato di uno
            total.endText();
        }
    }
}
