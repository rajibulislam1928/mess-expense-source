package com.messexpense.app;

import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Small, dependency-free XLSX reader/writer for the app's own backup workbook format. */
final class XlsxBackup {
    private static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final int MAX_ZIP_BYTES = 12 * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private XlsxBackup() {}

    static byte[] exportState(String json) throws Exception {
        JSONObject state = new JSONObject(json);
        validateState(state);
        String startDate = state.getString("startDate");
        JSONArray members = state.getJSONArray("members");
        JSONArray meals = state.getJSONArray("meals");
        JSONArray spend = state.getJSONArray("spend");
        int rows = members.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            add(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>");
            add(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>");
            add(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<workbook xmlns=\"" + MAIN_NS + "\" xmlns:r=\"" + REL_NS + "\"><sheets><sheet name=\"Tracker\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            add(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "</Relationships>");
            ByteArrayOutputStream sheetBytes = new ByteArrayOutputStream();
            XmlSerializer xml = Xml.newSerializer();
            xml.setOutput(sheetBytes, "UTF-8");
            xml.startDocument("UTF-8", true);
            xml.setPrefix("", MAIN_NS);
            xml.startTag(MAIN_NS, "worksheet");
            xml.startTag(MAIN_NS, "sheetData");
            xml.startTag(MAIN_NS, "row"); xml.attribute(null, "r", "1");
            inlineCell(xml, "A1", "Mess Expense Backup");
            xml.endTag(MAIN_NS, "row");
            xml.startTag(MAIN_NS, "row"); xml.attribute(null, "r", "2");
            inlineCell(xml, "A2", "Format Version"); numberCell(xml, "B2", "1"); xml.endTag(MAIN_NS, "row");
            xml.startTag(MAIN_NS, "row"); xml.attribute(null, "r", "3");
            inlineCell(xml, "A3", "Week Start"); inlineCell(xml, "B3", startDate); xml.endTag(MAIN_NS, "row");
            xml.startTag(MAIN_NS, "row"); xml.attribute(null, "r", "4");
            inlineCell(xml, "A4", "Member Count"); numberCell(xml, "B4", Integer.toString(rows)); xml.endTag(MAIN_NS, "row");
            xml.startTag(MAIN_NS, "row"); xml.attribute(null, "r", "6");
            inlineCell(xml, "A6", "Member");
            String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
            int col = 1;
            for (String day : days) {
                inlineCell(xml, colName(col++) + "6", day + " Lunch");
                inlineCell(xml, colName(col++) + "6", day + " Dinner");
            }
            inlineCell(xml, colName(col++) + "6", "Morning Spend");
            inlineCell(xml, colName(col++) + "6", "Night Spend");
            inlineCell(xml, colName(col) + "6", "Extra Spend");
            xml.endTag(MAIN_NS, "row");
            for (int i = 0; i < rows; i++) {
                int rowNum = i + 7;
                xml.startTag(MAIN_NS, "row"); xml.attribute(null, "r", Integer.toString(rowNum));
                inlineCell(xml, "A" + rowNum, members.getString(i));
                JSONArray week = meals.getJSONArray(i);
                col = 1;
                for (int d = 0; d < 7; d++) {
                    JSONArray pair = week.getJSONArray(d);
                    numberCell(xml, colName(col++) + rowNum, numberText(pair.getDouble(0)));
                    numberCell(xml, colName(col++) + rowNum, numberText(pair.getDouble(1)));
                }
                JSONObject expenses = spend.getJSONObject(i);
                numberCell(xml, colName(col++) + rowNum, numberText(expenses.getDouble("morning")));
                numberCell(xml, colName(col++) + rowNum, numberText(expenses.getDouble("night")));
                numberCell(xml, colName(col) + rowNum, numberText(expenses.getDouble("extra")));
                xml.endTag(MAIN_NS, "row");
            }
            xml.endTag(MAIN_NS, "sheetData");
            xml.endTag(MAIN_NS, "worksheet");
            xml.endDocument();
            add(zip, "xl/worksheets/sheet1.xml", sheetBytes.toString("UTF-8"));
            zip.finish();
        }
        return out.toByteArray();
    }

    static JSONObject importState(InputStream input) throws Exception {
        Map<String, byte[]> parts = readZip(input);
        if (!parts.containsKey("[Content_Types].xml") || !parts.containsKey("xl/workbook.xml"))
            throw new IOException("This is not a valid Excel .xlsx workbook.");
        List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? parseSharedStrings(parts.get("xl/sharedStrings.xml")) : new ArrayList<>();
        String sheetPath = findFirstSheet(parts.get("xl/workbook.xml"), parts.get("xl/_rels/workbook.xml.rels"));
        byte[] sheetBytes = parts.get(sheetPath);
        if (sheetBytes == null) throw new IOException("The workbook's first worksheet is missing.");
        List<List<String>> rows = parseRows(sheetBytes, shared);
        String formatVersion = cell(rows, 2, 1);
        String startDate = cell(rows, 3, 1);
        String memberCountText = cell(rows, 4, 1);
        if (!"1".equals(formatVersion) || !"Week Start".equals(cell(rows, 3, 0)) || !"Member Count".equals(cell(rows, 4, 0)))
            throw new IOException("This .xlsx file doesn't contain a supported Mess Expense backup.");
        if (!"Format Version".equals(cell(rows, 2, 0))) throw new IOException("Backup format marker is missing.");
        if (!isValidDate(startDate)) throw new IOException("The week start must be a valid YYYY-MM-DD date.");
        int count = Integer.parseInt(memberCountText);
        if (count < 1 || count > 100) throw new IOException("The workbook must contain 1–100 members.");
        int headerRow = 6;
        List<String> headers = new ArrayList<>();
        headers.add("Member");
        String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (String day : days) { headers.add(day + " Lunch"); headers.add(day + " Dinner"); }
        headers.add("Morning Spend"); headers.add("Night Spend"); headers.add("Extra Spend");
        for (int c = 0; c < headers.size(); c++) {
            if (!headers.get(c).equals(cell(rows, headerRow, c)))
                throw new IOException("The workbook columns don't match a Mess Expense backup.");
        }
        JSONArray members = new JSONArray(), meals = new JSONArray(), spend = new JSONArray();
        for (int i = 0; i < count; i++) {
            int rowNum = 7 + i;
            List<String> row = rowAt(rows, rowNum);
            if (row == null) throw new IOException("A member row is missing from the workbook.");
            String name = cell(rows, rowNum, 0).trim();
            if (name.isEmpty() || name.length() > 100) throw new IOException("A member name is blank or too long.");
            members.put(name);
            JSONArray daysArray = new JSONArray();
            int c = 1;
            for (int d = 0; d < 7; d++) {
                JSONArray pair = new JSONArray();
                pair.put(numberCellValue(row, c++, "meal count"));
                pair.put(numberCellValue(row, c++, "meal count"));
                daysArray.put(pair);
            }
            meals.put(daysArray);
            JSONObject expense = new JSONObject();
            expense.put("morning", numberCellValue(row, c++, "expense"));
            expense.put("night", numberCellValue(row, c++, "expense"));
            expense.put("extra", numberCellValue(row, c, "expense"));
            spend.put(expense);
        }
        for (int r = 7 + count; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row != null) for (String value : row) {
                if (value != null && !value.trim().isEmpty()) throw new IOException("The workbook has extra member rows beyond its member count.");
            }
        }
        JSONObject result = new JSONObject();
        result.put("startDate", startDate);
        result.put("members", members);
        result.put("meals", meals);
        result.put("spend", spend);
        validateState(result);
        return result;
    }

    private static Map<String, byte[]> readZip(InputStream input) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        int total = 0, count = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IOException("The workbook has too many files.");
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) throw new IOException("Invalid workbook entry path.");
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_ZIP_BYTES) throw new IOException("The workbook is too large to import safely.");
                    data.write(buffer, 0, n);
                }
                if (entries.put(name, data.toByteArray()) != null) throw new IOException("The workbook contains duplicate files.");
            }
        }
        return entries;
    }

    private static String findFirstSheet(byte[] workbook, byte[] relationships) throws Exception {
        if (workbook == null || relationships == null) throw new IOException("The Excel workbook structure is incomplete.");
        String relId = null;
        XmlPullParser parser = parser(workbook);
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG && "sheet".equals(parser.getName())) {
                relId = parser.getAttributeValue(REL_NS, "id");
                if (relId == null) relId = parser.getAttributeValue(null, "r:id");
                break;
            }
        }
        if (relId == null) throw new IOException("The workbook has no worksheet.");
        parser = parser(relationships);
        String target = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG && "Relationship".equals(parser.getName()) && relId.equals(parser.getAttributeValue(null, "Id"))) {
                target = parser.getAttributeValue(null, "Target"); break;
            }
        }
        if (target == null || target.contains("..") || target.contains("\\") || target.contains(":") || target.startsWith("//"))
            throw new IOException("The workbook worksheet link is invalid.");
        String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
        return path;
    }

    private static List<String> parseSharedStrings(byte[] bytes) throws Exception {
        List<String> values = new ArrayList<>();
        XmlPullParser parser = parser(bytes);
        boolean inItem = false;
        StringBuilder text = new StringBuilder();
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG && "si".equals(parser.getName())) { inItem = true; text.setLength(0); }
            else if (event == XmlPullParser.START_TAG && inItem && "t".equals(parser.getName())) text.append(parser.nextText());
            else if (event == XmlPullParser.END_TAG && "si".equals(parser.getName())) { values.add(text.toString()); inItem = false; }
        }
        return values;
    }

    private static List<List<String>> parseRows(byte[] bytes, List<String> sharedStrings) throws Exception {
        XmlPullParser parser = parser(bytes);
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = null;
        String cellType = "", cellValue = "";
        int currentCol = -1, nextRow = 0;
        boolean inCell = false, formula = false;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG && "row".equals(parser.getName())) {
                String ref = parser.getAttributeValue(null, "r");
                int rowNum = ref == null ? nextRow + 1 : Integer.parseInt(ref);
                if (rowNum < 1 || rowNum > 10000) throw new IOException("Invalid worksheet row number.");
                while (rows.size() < rowNum) rows.add(null);
                currentRow = new ArrayList<>();
                rows.set(rowNum - 1, currentRow);
                nextRow = rowNum;
            } else if (event == XmlPullParser.START_TAG && "c".equals(parser.getName()) && currentRow != null) {
                inCell = true; formula = false; cellValue = "";
                cellType = parser.getAttributeValue(null, "t");
                String ref = parser.getAttributeValue(null, "r");
                currentCol = ref == null ? currentRow.size() : columnIndex(ref);
                if (currentCol < 0 || currentCol > 64) throw new IOException("Invalid worksheet cell reference.");
            } else if (event == XmlPullParser.START_TAG && inCell && "f".equals(parser.getName())) {
                formula = true;
            } else if (event == XmlPullParser.START_TAG && inCell && "v".equals(parser.getName())) {
                cellValue = parser.nextText();
            } else if (event == XmlPullParser.START_TAG && inCell && "t".equals(parser.getName()) && "inlineStr".equals(cellType)) {
                cellValue += parser.nextText();
            } else if (event == XmlPullParser.END_TAG && "c".equals(parser.getName()) && inCell) {
                if (formula) throw new IOException("Formula cells are not supported in a backup.");
                if ("s".equals(cellType)) {
                    int index = Integer.parseInt(cellValue);
                    if (index < 0 || index >= sharedStrings.size()) throw new IOException("Invalid shared string in workbook.");
                    cellValue = sharedStrings.get(index);
                }
                while (currentRow.size() <= currentCol) currentRow.add("");
                currentRow.set(currentCol, cellValue);
                inCell = false;
            } else if (event == XmlPullParser.END_TAG && "row".equals(parser.getName())) currentRow = null;
        }
        return rows;
    }

    private static XmlPullParser parser(byte[] bytes) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        try { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false); } catch (Exception ignored) {}
        parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");
        return parser;
    }

    private static int columnIndex(String ref) throws IOException {
        int result = 0, chars = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'a' && c <= 'z') c = (char) (c - 'a' + 'A');
            if (c < 'A' || c > 'Z') break;
            result = result * 26 + c - 'A' + 1; chars++;
        }
        if (chars == 0) throw new IOException("Invalid worksheet cell reference.");
        return result - 1;
    }

    private static String cell(List<List<String>> rows, int row, int col) {
        List<String> values = rowAt(rows, row);
        return values == null || col >= values.size() || values.get(col) == null ? "" : values.get(col);
    }
    private static List<String> rowAt(List<List<String>> rows, int oneBasedRow) {
        return oneBasedRow > 0 && oneBasedRow <= rows.size() ? rows.get(oneBasedRow - 1) : null;
    }
    private static double numberCellValue(List<String> row, int col, String label) throws Exception {
        if (col >= row.size() || row.get(col) == null || row.get(col).trim().isEmpty()) throw new IOException("A " + label + " value is missing.");
        BigDecimal value;
        try { value = new BigDecimal(row.get(col).trim()); }
        catch (NumberFormatException e) { throw new IOException("A " + label + " value is not a number."); }
        if (value.signum() < 0) throw new IOException("Negative values aren't valid in this backup.");
        double number = value.doubleValue();
        if (Double.isInfinite(number) || Double.isNaN(number)) throw new IOException("A number is outside the supported range.");
        return number;
    }

    private static void validateState(JSONObject state) throws Exception {
        String date = state.optString("startDate", "");
        if (!isValidDate(date)) throw new IOException("The week start must be a valid YYYY-MM-DD date.");
        JSONArray members = state.getJSONArray("members"), meals = state.getJSONArray("meals"), spend = state.getJSONArray("spend");
        int n = members.length();
        if (n < 1 || n > 100 || meals.length() != n || spend.length() != n) throw new IOException("Backup data must have 1–100 matching member, meal and expense rows.");
        for (int i = 0; i < n; i++) {
            String name = members.getString(i);
            if (name.trim().isEmpty() || name.length() > 100) throw new IOException("A member name is blank or too long.");
            JSONArray week = meals.getJSONArray(i);
            if (week.length() != 7) throw new IOException("Each member must have seven days of meal values.");
            for (int d = 0; d < 7; d++) {
                JSONArray pair = week.getJSONArray(d);
                if (pair.length() != 2) throw new IOException("Each day must have lunch and dinner values.");
                for (int j = 0; j < 2; j++) if (!validNumber(pair.get(j))) throw new IOException("Meals must be non-negative numbers.");
            }
            JSONObject expenses = spend.getJSONObject(i);
            for (String key : new String[]{"morning", "night", "extra"}) if (!validNumber(expenses.get(key))) throw new IOException("Expenses must be non-negative numbers.");
        }
    }
    private static boolean isValidDate(String value) {
        if (value == null || value.isEmpty()) return true;
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        java.util.Date parsed = format.parse(value, position);
        return parsed != null && position.getIndex() == value.length() && value.equals(format.format(parsed));
    }
    private static boolean validNumber(Object value) {
        if (!(value instanceof Number)) return false;
        double d = ((Number) value).doubleValue(); return !Double.isInfinite(d) && !Double.isNaN(d) && d >= 0;
    }
    private static String numberText(double n) { return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString(); }
    private static String colName(int zeroBased) {
        int n = zeroBased + 1; StringBuilder name = new StringBuilder();
        while (n > 0) { int r = (n - 1) % 26; name.insert(0, (char) ('A' + r)); n = (n - 1) / 26; }
        return name.toString();
    }
    private static void add(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes("UTF-8")); zip.closeEntry();
    }
    private static void inlineCell(XmlSerializer xml, String ref, String value) throws Exception {
        xml.startTag(MAIN_NS, "c"); xml.attribute(null, "r", ref); xml.attribute(null, "t", "inlineStr");
        xml.startTag(MAIN_NS, "is"); xml.startTag(MAIN_NS, "t");
        if (value.startsWith(" ") || value.endsWith(" ")) xml.attribute("http://www.w3.org/XML/1998/namespace", "space", "preserve");
        xml.text(value); xml.endTag(MAIN_NS, "t"); xml.endTag(MAIN_NS, "is"); xml.endTag(MAIN_NS, "c");
    }
    private static void numberCell(XmlSerializer xml, String ref, String value) throws Exception {
        xml.startTag(MAIN_NS, "c"); xml.attribute(null, "r", ref); xml.startTag(MAIN_NS, "v"); xml.text(value); xml.endTag(MAIN_NS, "v"); xml.endTag(MAIN_NS, "c");
    }
}
