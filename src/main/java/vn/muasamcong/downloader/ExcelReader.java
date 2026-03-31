package vn.muasamcong.downloader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class ExcelReader {

    private ExcelReader() {
    }

    public static List<String> readKeywords(Path excelPath) {
        if (!Files.exists(excelPath)) {
            throw new IllegalArgumentException("Excel file not found: " + excelPath.toAbsolutePath());
        }

        Set<String> keywords = new LinkedHashSet<>();
        DataFormatter formatter = new DataFormatter();

        try (InputStream in = Files.newInputStream(excelPath); Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                Cell firstCell = row.getCell(0);
                if (firstCell == null) {
                    continue;
                }

                String keyword = formatter.formatCellValue(firstCell).trim();
                if (!keyword.isEmpty() && !"keyword".equalsIgnoreCase(keyword)) {
                    keywords.add(keyword);
                }
            }
        } catch (IOException ex) {
            throw new RuntimeException("Unable to read Excel file: " + excelPath.toAbsolutePath(), ex);
        }

        return new ArrayList<>(keywords);
    }

    public static void createSampleExcel(Path excelPath) {
        Path parent = excelPath.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ex) {
            throw new RuntimeException("Unable to create sample folder for Excel file.", ex);
        }

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Keywords");
            sheet.createRow(0).createCell(0).setCellValue("keyword");
            sheet.createRow(1).createCell(0).setCellValue("Thi cong xay dung");
            sheet.createRow(2).createCell(0).setCellValue("Mua sam thiet bi");
            sheet.createRow(3).createCell(0).setCellValue("Tu van giam sat");

            try (OutputStream out = Files.newOutputStream(excelPath)) {
                workbook.write(out);
            }
        } catch (IOException ex) {
            throw new RuntimeException("Unable to write sample Excel file.", ex);
        }
    }
}
