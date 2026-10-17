package com.cronzph.salarytracker.util;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Minimum na .xlsx writer at reader — walang external dependency.
 *
 * <p>Ang .xlsx ay isang ZIP na puno ng XML. Isinusulat natin ang mga cell gamit ang
 * {@code inlineStr} (imbes na {@code sharedStrings}) para simple lang ang parehong
 * pagsulat at pagbasa.
 *
 * <p>Ang reader ay tumatanggap din ng {@code sharedStrings} — kaya kahit buksan mo
 * ang file sa Excel at i-save ulit, nababasa pa rin.
 */
public final class XlsxUtils {

    private static final String NS_MAIN =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PKG_REL =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String NS_CT =
            "http://schemas.openxmlformats.org/package/2006/content-types";

    /** Excel limit — mas mahaba dito ay tinatanggihan. */
    private static final int MAX_CELL = 32767;

    /** Isang worksheet. Ang bawat row ay listahan ng String (text) o Double (numero). */
    public static final class Sheet {
        public final String name;
        public final List<List<Object>> rows = new ArrayList<>();

        public Sheet(String name) {
            this.name = name;
        }

        public Sheet row(Object... cells) {
            List<Object> r = new ArrayList<>(cells.length);
            for (Object c : cells) {
                r.add(c);
            }
            rows.add(r);
            return this;
        }
    }

    private XlsxUtils() {
    }

    // ================================================================== write

    public static void write(OutputStream out, List<Sheet> sheets) throws IOException {
        if (sheets == null || sheets.isEmpty()) {
            throw new IOException("No sheet to write");
        }

        ZipOutputStream zos = new ZipOutputStream(out);
        try {
            put(zos, "[Content_Types].xml", contentTypes(sheets.size()));
            put(zos, "_rels/.rels", rootRels());
            put(zos, "xl/workbook.xml", workbook(sheets));
            put(zos, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
            put(zos, "xl/styles.xml", styles());
            for (int i = 0; i < sheets.size(); i++) {
                put(zos, "xl/worksheets/sheet" + (i + 1) + ".xml", worksheet(sheets.get(i)));
            }
            zos.finish();
        } finally {
            try {
                zos.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void put(ZipOutputStream zos, String name, String body) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(body.getBytes("UTF-8"));
        zos.closeEntry();
    }

    private static String contentTypes(int sheetCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Types xmlns=\"").append(NS_CT).append("\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"")
                .append(NS_PKG_REL).append("\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"")
                .append("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"")
                .append("application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheetCount; i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"")
                    .append("application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        sb.append("</Types>");
        return sb.toString();
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"" + NS_PKG_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/officeDocument\""
                + " Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String workbook(List<Sheet> sheets) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"")
                .append(NS_REL).append("\"><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(esc(sheetName(sheets.get(i).name, i)))
                    .append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</sheets></workbook>");
        return sb.toString();
    }

    /** Excel: max 31 chars, bawal ang : \ / ? * [ ] */
    private static String sheetName(String raw, int index) {
        String base = raw == null || raw.trim().isEmpty() ? "Sheet" + (index + 1) : raw.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 31; i++) {
            char ch = base.charAt(i);
            if (ch == ':' || ch == '\\' || ch == '/' || ch == '?' || ch == '*'
                    || ch == '[' || ch == ']') {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String workbookRels(int sheetCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<Relationships xmlns=\"").append(NS_PKG_REL).append("\">");
        for (int i = 1; i <= sheetCount; i++) {
            sb.append("<Relationship Id=\"rId").append(i).append("\" Type=\"")
                    .append(NS_REL).append("/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(sheetCount + 1).append("\" Type=\"")
                .append(NS_REL).append("/styles\" Target=\"styles.xml\"/>");
        sb.append("</Relationships>");
        return sb.toString();
    }

    /** s=1 bold (header), s=2 "0.00" (pera). */
    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"" + NS_MAIN + "\">"
                + "<fonts count=\"2\">"
                + "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "</fonts>"
                // Kailangang may 2 fill — kinakailangan ito ng Excel.
                + "<fills count=\"2\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "</fills>"
                + "<borders count=\"1\"><border/></borders>"
                + "<cellStyleXfs count=\"1\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                + "</cellStyleXfs>"
                + "<cellXfs count=\"3\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"2\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\">"
                + "<cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>"
                + "</cellStyles>"
                + "</styleSheet>";
    }

    private static String worksheet(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        sb.append("<worksheet xmlns=\"").append(NS_MAIN).append("\">");

        int cols = 0;
        for (List<Object> row : sheet.rows) {
            cols = Math.max(cols, row.size());
        }
        if (cols > 0) {
            sb.append("<cols>");
            for (int i = 1; i <= cols; i++) {
                sb.append("<col min=\"").append(i).append("\" max=\"").append(i)
                        .append("\" width=\"14\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }

        sb.append("<sheetData>");
        for (int r = 0; r < sheet.rows.size(); r++) {
            List<Object> row = sheet.rows.get(r);
            int rowNum = r + 1;
            sb.append("<row r=\"").append(rowNum).append("\">");
            for (int c = 0; c < row.size(); c++) {
                Object cell = row.get(c);
                if (cell == null) continue;
                String ref = colName(c) + rowNum;
                if (cell instanceof Number) {
                    double d = ((Number) cell).doubleValue();
                    sb.append("<c r=\"").append(ref).append("\" s=\"2\"><v>")
                            .append(num(d)).append("</v></c>");
                } else {
                    String text = String.valueOf(cell);
                    int style = (r == 0) ? 1 : 0;
                    sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\" s=\"")
                            .append(style).append("\"><is><t xml:space=\"preserve\">")
                            .append(esc(text)).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    /** 0 -> A, 25 -> Z, 26 -> AA */
    private static String colName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.format(Locale.US, "%.10f", d);
    }

    /**
     * XML-escape at tanggalin ang mga control character na bawal sa XML 1.0 —
     * kapag may ganoon sa notes, tatanggi ang Excel na buksan ang file.
     */
    private static String esc(String raw) {
        if (raw == null) return "";
        String s = raw.length() > MAX_CELL ? raw.substring(0, MAX_CELL) : raw;
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                case '\t':
                case '\n':
                case '\r':
                    sb.append(ch);
                    break;
                default:
                    if (ch < 0x20) {
                        // bawal sa XML — laktawan
                        break;
                    }
                    sb.append(ch);
            }
        }
        return sb.toString();
    }

    // =================================================================== read

    /** Basahin ang lahat ng worksheet, sa pagkakasunod ng zip entry. */
    public static List<Sheet> read(InputStream in) throws IOException {
        List<String> sheetXml = new ArrayList<>();
        List<String> shared = new ArrayList<>();

        ZipInputStream zis = new ZipInputStream(in);
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                String lower = name.toLowerCase(Locale.US);
                if (lower.startsWith("xl/worksheets/") && lower.endsWith(".xml")) {
                    sheetXml.add(readEntry(zis));
                } else if (lower.equals("xl/sharedstrings.xml")) {
                    shared = parseSharedStrings(readEntry(zis));
                }
            }
        } finally {
            try {
                zis.close();
            } catch (IOException ignored) {
            }
        }

        List<Sheet> out = new ArrayList<>();
        for (String xml : sheetXml) {
            Sheet sheet = parseSheet(xml, shared);
            if (!sheet.rows.isEmpty()) out.add(sheet);
        }
        return out;
    }

    private static String readEntry(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString("UTF-8");
    }

    private static List<String> parseSharedStrings(String xml) {
        List<String> out = new ArrayList<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            StringBuilder current = null;
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "si".equals(p.getName())) {
                    current = new StringBuilder();
                } else if (event == XmlPullParser.START_TAG && "t".equals(p.getName())
                        && current != null) {
                    current.append(p.nextText());
                } else if (event == XmlPullParser.END_TAG && "si".equals(p.getName())
                        && current != null) {
                    out.add(current.toString());
                    current = null;
                }
                event = p.next();
            }
        } catch (Exception ignored) {
            // kung sira, babalik ang kung ano ang nabasa na
        }
        return out;
    }

    private static Sheet parseSheet(String xml, List<String> shared) throws IOException {
        Sheet sheet = new Sheet("");
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));

            List<Object> row = null;
            String cellType = null;
            int cellCol = -1;
            StringBuilder value = null;   // <v>
            StringBuilder inline = null;  // <t> sa loob ng <is>
            boolean inValue = false;
            boolean inText = false;

            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = p.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("row".equals(tag)) {
                        row = new ArrayList<>();
                    } else if ("c".equals(tag)) {
                        cellType = p.getAttributeValue(null, "t");
                        cellCol = colIndex(p.getAttributeValue(null, "r"));
                        value = null;
                        inline = null;
                    } else if ("v".equals(tag)) {
                        value = new StringBuilder();
                        inValue = true;
                    } else if ("t".equals(tag)) {
                        inline = new StringBuilder();
                        inText = true;
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (inValue && value != null) value.append(p.getText());
                    else if (inText && inline != null) inline.append(p.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    if ("v".equals(tag)) {
                        inValue = false;
                    } else if ("t".equals(tag)) {
                        inText = false;
                    } else if ("c".equals(tag)) {
                        if (row != null && cellCol >= 0) {
                            putCell(row, cellCol, resolve(cellType, value, inline, shared));
                        }
                        cellType = null;
                        cellCol = -1;
                        value = null;
                        inline = null;
                    } else if ("row".equals(tag)) {
                        if (row != null) sheet.rows.add(row);
                        row = null;
                    }
                }
                event = p.next();
            }
        } catch (Exception e) {
            throw new IOException("sirang worksheet: " + e.getMessage());
        }
        return sheet;
    }

    private static Object resolve(String type, StringBuilder value, StringBuilder inline,
                                  List<String> shared) {
        if ("inlineStr".equals(type)) {
            return inline == null ? "" : inline.toString();
        }
        String raw = value == null ? null : value.toString();
        if (raw == null) {
            return inline == null ? "" : inline.toString();
        }
        if ("s".equals(type)) {
            try {
                int idx = Integer.parseInt(raw.trim());
                return idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if ("str".equals(type) || "e".equals(type)) {
            return raw;
        }
        if ("b".equals(type)) {
            return "1".equals(raw.trim()) ? Boolean.TRUE : Boolean.FALSE;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static void putCell(List<Object> row, int col, Object val) {
        while (row.size() <= col) {
            row.add(null);
        }
        row.set(col, val);
    }

    /** "C5" -> 2 (0-based column). -1 kapag hindi mabasa. */
    private static int colIndex(String ref) {
        if (ref == null) return -1;
        int col = 0;
        boolean any = false;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                col = col * 26 + (ch - 'A' + 1);
                any = true;
            } else if (ch >= 'a' && ch <= 'z') {
                col = col * 26 + (ch - 'a' + 1);
                any = true;
            } else {
                break;
            }
        }
        return any ? col - 1 : -1;
    }
}
