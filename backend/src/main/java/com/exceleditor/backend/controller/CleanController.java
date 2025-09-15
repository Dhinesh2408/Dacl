package com.exceleditor.backend.controller;

import com.opencsv.CSVWriter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class CleanController {

    @PostMapping(value = "/clean", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> clean(@RequestParam("file") MultipartFile file,
                                        @RequestParam("columns") String columnsCsv,
                                        @RequestParam(value = "trim", defaultValue = "true") boolean trim,
                                        @RequestParam(value = "collapseSpaces", defaultValue = "true") boolean collapseSpaces,
                                        @RequestParam(value = "textCase", defaultValue = "none") String textCase,
                                        @RequestParam(value = "dateFormat", defaultValue = "none") String dateFormat,
                                        @RequestParam(value = "dedupeKeys", required = false) String dedupeKeys,
                                        @RequestParam(value = "dropEmptyRows", defaultValue = "true") boolean dropEmptyRows,
                                        @RequestParam(value = "dropEmptyCols", defaultValue = "true") boolean dropEmptyCols,
                                        @RequestParam(value = "normalizeTypes", defaultValue = "false") boolean normalizeTypes,
                                        @RequestParam(value = "validateEmail", defaultValue = "false") boolean validateEmail,
                                        @RequestParam(value = "removeInvalidEmails", defaultValue = "false") boolean removeInvalidEmails,
                                        @RequestParam(value = "validateUrl", defaultValue = "false") boolean validateUrl,
                                        @RequestParam(value = "removeInvalidUrls", defaultValue = "false") boolean removeInvalidUrls,
                                        @RequestParam(value = "outputFormat", defaultValue = "csv") String outputFormat,
                                        @RequestParam(value = "keepOrder", defaultValue = "true") boolean keepOrder) throws Exception {
        List<String> columns = Arrays.stream(columnsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (columns.isEmpty()) {
            return ResponseEntity.badRequest().body("No columns provided".getBytes(StandardCharsets.UTF_8));
        }

        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("file");
        String lower = name.toLowerCase(Locale.ROOT);

        ProcessedData processed;
        if (lower.endsWith(".csv")) {
            processed = processCsv(file.getInputStream(), columns, trim, collapseSpaces, textCase, dateFormat,
                dedupeKeys, dropEmptyRows, dropEmptyCols, normalizeTypes,
                validateEmail, removeInvalidEmails, validateUrl, removeInvalidUrls);
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            processed = processXlsx(file.getInputStream(), columns, trim, collapseSpaces, textCase, dateFormat,
                dedupeKeys, dropEmptyRows, dropEmptyCols, normalizeTypes,
                validateEmail, removeInvalidEmails, validateUrl, removeInvalidUrls);
        } else {
            return ResponseEntity.badRequest().body("Unsupported file type".getBytes(StandardCharsets.UTF_8));
        }

        if ("xlsx".equalsIgnoreCase(outputFormat)) {
            byte[] xlsx = writeXlsx(processed.header, processed.rows);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=cleaned_" + name.replaceAll("\\.(xlsx|xls|csv)$", ".xlsx"))
                    .body(xlsx);
        } else {
            byte[] csv = writeCsv(processed.header, processed.rows);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=cleaned_" + name.replaceAll("\\.(xlsx|xls)$", ".csv"))
                    .body(csv);
        }
    }

    private record ProcessedData(String[] header, List<String[]> rows) {}

    private ProcessedData processCsv(InputStream in, List<String> keepColumns,
                                     boolean trim, boolean collapseSpaces, String textCase, String dateFormat,
                                     String dedupeKeysCsv, boolean dropEmptyRows, boolean dropEmptyCols, boolean normalizeTypes,
                                     boolean validateEmail, boolean removeInvalidEmails, boolean validateUrl, boolean removeInvalidUrls) throws Exception {
        Scanner scanner = new Scanner(in, StandardCharsets.UTF_8);
        scanner.useDelimiter("\\A");
        String text = scanner.hasNext() ? scanner.next() : "";

        List<String[]> rows = new ArrayList<>();
        com.opencsv.CSVReader reader = new com.opencsv.CSVReader(new java.io.StringReader(text));
        String[] row;
        while ((row = reader.readNext()) != null) {
            rows.add(row);
        }
        reader.close();
        if (rows.isEmpty()) return new ProcessedData(new String[0], List.of());

        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        String[] header = rows.get(0);
        for (int i = 0; i < header.length; i++) headerIndex.put(header[i], i);

        List<Integer> indexes = keepColumns.stream().map(headerIndex::get).filter(Objects::nonNull).toList();
        if (dropEmptyCols) indexes = dropEmptyColumnsCsv(rows, indexes);

        List<String[]> outRows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Integer> dedupeIdx = parseDedupeIndexes(dedupeKeysCsv, headerIndex);

        for (int r = 1; r < rows.size(); r++) {
            String[] src = rows.get(r);
            if (dropEmptyRows && isRowEmptyCsv(src, indexes)) continue;
            if (!dedupeIdx.isEmpty()) {
                String key = buildKeyCsv(src, dedupeIdx);
                if (!seen.add(key)) continue;
            }
            String[] out = new String[indexes.size()];
            for (int i = 0; i < indexes.size(); i++) {
                int idx = indexes.get(i);
                String val = idx < src.length ? src[idx] : "";
                String t = transform(val, trim, collapseSpaces, textCase, dateFormat);
                if (normalizeTypes) t = normalizeType(t);
                if (validateEmail && !isValidEmail(t)) { if (removeInvalidEmails) { out = null; break; } }
                if (validateUrl && !isValidUrl(t)) { if (removeInvalidUrls) { out = null; break; } }
                if (out != null) out[i] = t;
            }
            if (out != null) outRows.add(out);
        }

        String[] outHeader = buildOutHeader(header, indexes);
        return new ProcessedData(outHeader, outRows);
    }

    private ProcessedData processXlsx(InputStream in, List<String> keepColumns,
                                      boolean trim, boolean collapseSpaces, String textCase, String dateFormat,
                                      String dedupeKeysCsv, boolean dropEmptyRows, boolean dropEmptyCols, boolean normalizeTypes,
                                      boolean validateEmail, boolean removeInvalidEmails, boolean validateUrl, boolean removeInvalidUrls) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) return new ProcessedData(new String[0], List.of());
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return new ProcessedData(new String[0], List.of());

            Map<String, Integer> headerIndex = new LinkedHashMap<>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                String val = headerRow.getCell(i) == null ? "" : headerRow.getCell(i).toString();
                headerIndex.put(val, i);
            }
            List<Integer> indexes = keepColumns.stream().map(headerIndex::get).filter(Objects::nonNull).toList();
            if (dropEmptyCols) indexes = dropEmptyColumnsXlsx(sheet, indexes);

            List<String[]> outRows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<Integer> dedupeIdx = parseDedupeIndexes(dedupeKeysCsv, headerIndex);

            int lastRow = sheet.getLastRowNum();
            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (dropEmptyRows && isRowEmptyXlsx(row, indexes)) continue;
                if (!dedupeIdx.isEmpty()) {
                    String key = buildKeyXlsx(row, dedupeIdx);
                    if (!seen.add(key)) continue;
                }
                String[] out = new String[indexes.size()];
                for (int i = 0; i < indexes.size(); i++) {
                    int idx = indexes.get(i);
                    String cellVal = (row != null && row.getCell(idx) != null) ? row.getCell(idx).toString() : "";
                    String t = transform(cellVal, trim, collapseSpaces, textCase, dateFormat);
                    if (normalizeTypes) t = normalizeType(t);
                    if (validateEmail && !isValidEmail(t)) { if (removeInvalidEmails) { out = null; break; } }
                    if (validateUrl && !isValidUrl(t)) { if (removeInvalidUrls) { out = null; break; } }
                    if (out != null) out[i] = t;
                }
                if (out != null) outRows.add(out);
            }

            String[] outHeader = buildOutHeader(headerRow, indexes);
            return new ProcessedData(outHeader, outRows);
        }
    }

    private String[] buildOutHeader(String[] header, List<Integer> indexes) {
        String[] outHeader = new String[indexes.size()];
        for (int i = 0; i < indexes.size(); i++) outHeader[i] = indexes.get(i) < header.length ? header[indexes.get(i)] : "";
        return outHeader;
    }

    private String[] buildOutHeader(Row headerRow, List<Integer> indexes) {
        String[] outHeader = new String[indexes.size()];
        for (int i = 0; i < indexes.size(); i++) {
            int idx = indexes.get(i);
            outHeader[i] = headerRow.getCell(idx) == null ? "" : headerRow.getCell(idx).toString();
        }
        return outHeader;
    }

    private List<Integer> parseDedupeIndexes(String csv, Map<String, Integer> headerIndex) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> list = new ArrayList<>();
        for (String k : csv.split(",")) {
            Integer i = headerIndex.get(k.trim());
            if (i != null) list.add(i);
        }
        return list;
    }

    private boolean isRowEmptyCsv(String[] row, List<Integer> indexes) {
        for (Integer idx : indexes) {
            String v = idx < row.length ? row[idx] : "";
            if (v != null && !v.trim().isEmpty()) return false;
        }
        return true;
    }

    private boolean isRowEmptyXlsx(Row row, List<Integer> indexes) {
        for (Integer idx : indexes) {
            String v = (row != null && row.getCell(idx) != null) ? row.getCell(idx).toString() : "";
            if (v != null && !v.trim().isEmpty()) return false;
        }
        return true;
    }

    private List<Integer> dropEmptyColumnsCsv(List<String[]> rows, List<Integer> indexes) {
        List<Integer> nonEmpty = new ArrayList<>();
        for (Integer idx : indexes) {
            boolean allEmpty = true;
            for (int r = 1; r < rows.size(); r++) {
                String[] rr = rows.get(r);
                String val = idx < rr.length ? rr[idx] : "";
                if (val != null && !val.trim().isEmpty()) { allEmpty = false; break; }
            }
            if (!allEmpty) nonEmpty.add(idx);
        }
        return nonEmpty;
    }

    private List<Integer> dropEmptyColumnsXlsx(Sheet sheet, List<Integer> indexes) {
        List<Integer> nonEmpty = new ArrayList<>();
        for (Integer idx : indexes) {
            boolean allEmpty = true;
            int lastRow = sheet.getLastRowNum();
            for (int r = 1; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                String val = (row != null && row.getCell(idx) != null) ? row.getCell(idx).toString() : "";
                if (val != null && !val.trim().isEmpty()) { allEmpty = false; break; }
            }
            if (!allEmpty) nonEmpty.add(idx);
        }
        return nonEmpty;
    }

    private String buildKeyCsv(String[] row, List<Integer> idx) {
        StringBuilder sb = new StringBuilder();
        for (Integer i : idx) {
            if (sb.length() > 0) sb.append("|");
            sb.append(i < row.length ? row[i] : "");
        }
        return sb.toString();
    }

    private String buildKeyXlsx(Row row, List<Integer> idx) {
        StringBuilder sb = new StringBuilder();
        for (Integer i : idx) {
            if (sb.length() > 0) sb.append("|");
            String v = (row != null && row.getCell(i) != null) ? row.getCell(i).toString() : "";
            sb.append(v);
        }
        return sb.toString();
    }

    private byte[] writeCsv(String[] header, List<String[]> rows) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CSVWriter writer = new CSVWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));
        writer.writeNext(header);
        for (String[] r : rows) writer.writeNext(r);
        writer.close();
        return baos.toByteArray();
    }

    private byte[] writeXlsx(String[] header, List<String[]> rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("Cleaned");
            int r = 0;
            Row hr = sh.createRow(r++);
            for (int c = 0; c < header.length; c++) hr.createCell(c).setCellValue(header[c]);
            for (String[] row : rows) {
                Row rr = sh.createRow(r++);
                for (int c = 0; c < row.length; c++) rr.createCell(c).setCellValue(row[c] == null ? "" : row[c]);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        }
    }

    private String transform(String value, boolean trim, boolean collapseSpaces, String textCase, String dateFormat) {
        String v = value == null ? "" : value;
        if (trim) v = v.trim();
        if (collapseSpaces) v = v.replaceAll("\\s+", " ");
        switch (textCase) {
            case "lower" -> v = v.toLowerCase(Locale.ROOT);
            case "upper" -> v = v.toUpperCase(Locale.ROOT);
            case "title" -> v = toTitleCase(v);
            default -> {}
        }
        if ("iso".equalsIgnoreCase(dateFormat)) v = toIsoDateOrSame(v);
        return v;
    }

    private String toTitleCase(String input) {
        if (input.isEmpty()) return input;
        String[] parts = input.split(" ");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            parts[i] = p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1).toLowerCase(Locale.ROOT);
        }
        return String.join(" ", parts);
    }

    private String toIsoDateOrSame(String v) {
        List<java.time.format.DateTimeFormatter> fmts = List.of(
                java.time.format.DateTimeFormatter.ofPattern("M/d/uuuu"),
                java.time.format.DateTimeFormatter.ofPattern("d/M/uuuu"),
                java.time.format.DateTimeFormatter.ofPattern("uuuu-M-d"),
                java.time.format.DateTimeFormatter.ISO_DATE
        );
        for (var f : fmts) {
            try {
                java.time.LocalDate d = java.time.LocalDate.parse(v, f);
                return d.toString();
            } catch (Exception ignore) { }
        }
        return v;
    }

    private String normalizeType(String v) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) return s;
        if (s.matches("^[\\$€£]\\s?[-+]?([0-9]{1,3}(,[0-9]{3})*|[0-9]+)(\\.[0-9]+)?$")) s = s.replaceAll("[\\$€£,]", "");
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes")) return "true";
        if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("no")) return "false";
        if (s.matches("^[-+]?([0-9]{1,3}(,[0-9]{3})*|[0-9]+)(\\.[0-9]+)?$")) return s.replace(",", "");
        return s;
    }

    private boolean isValidEmail(String v) {
        return v == null || v.isEmpty() || v.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    private boolean isValidUrl(String v) {
        if (v == null || v.isEmpty()) return true;
        try { new java.net.URL(v); return true; } catch (Exception e) { return false; }
    }
}
