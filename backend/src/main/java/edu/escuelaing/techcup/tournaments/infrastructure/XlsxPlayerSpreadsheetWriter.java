package edu.escuelaing.techcup.tournaments.infrastructure;

import edu.escuelaing.techcup.tournaments.application.PlayerExportRow;
import edu.escuelaing.techcup.tournaments.application.PlayerExportSheet;
import edu.escuelaing.techcup.tournaments.application.PlayerSpreadsheetWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Apache POI adapter for {@link PlayerSpreadsheetWriter}: an Office Open XML workbook (.xlsx)
 * with a bold, frozen header row, birth dates as real Excel dates (dd/mm/yyyy), numbers as
 * numbers, phones as text (a "+57..." string, never a number) and empty cells for missing values.
 *
 * <p>Column widths are computed from the longest text of each column instead of
 * {@code Sheet.autoSizeColumn}: the latter measures text with AWT fonts, which a headless Linux
 * server may not have installed (it then fails or returns nonsense), and is slow.
 */
@Component
public class XlsxPlayerSpreadsheetWriter implements PlayerSpreadsheetWriter {

    static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final List<String> HEADERS = List.of(
            "Nombre", "Correo", "Celular", "Tipo documento", "Número documento", "Fecha de nacimiento", "Edad",
            "Relación con la Escuela", "Programa académico", "Semestre", "Equipo", "Capitán", "Posición",
            "Dorsal", "Estado inscripción");
    static final String DATE_FORMAT = "dd/mm/yyyy";
    private static final int BIRTH_DATE_COLUMN = 5;
    /** Width of the date column in characters ("dd/mm/yyyy" plus padding). */
    private static final int DATE_WIDTH = 12;
    private static final int MIN_WIDTH = 6;
    private static final int MAX_WIDTH = 60;

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public String extension() {
        return "xlsx";
    }

    @Override
    public byte[] write(List<PlayerExportSheet> sheets) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(DATE_FORMAT));

            for (PlayerExportSheet data : sheets) {
                Sheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(data.name()));
                int[] widths = new int[HEADERS.size()];
                Row header = sheet.createRow(0);
                for (int column = 0; column < HEADERS.size(); column++) {
                    Cell cell = header.createCell(column);
                    cell.setCellValue(HEADERS.get(column));
                    cell.setCellStyle(headerStyle);
                    widths[column] = HEADERS.get(column).length();
                }
                int rowIndex = 1;
                for (PlayerExportRow player : data.rows()) {
                    writeRow(sheet.createRow(rowIndex++), player, dateStyle, widths);
                }
                sheet.createFreezePane(0, 1);
                widths[BIRTH_DATE_COLUMN] = Math.max(widths[BIRTH_DATE_COLUMN], DATE_WIDTH);
                for (int column = 0; column < widths.length; column++) {
                    int characters = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, widths[column] + 2));
                    sheet.setColumnWidth(column, characters * 256);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No fue posible generar el archivo de Excel.", e);
        }
    }

    private static void writeRow(Row row, PlayerExportRow player, CellStyle dateStyle, int[] widths) {
        Object[] values = {
                player.fullName(), player.email(), player.phoneNumber(), player.documentType(), player.documentNumber(),
                player.birthDate(), player.age(), player.schoolRelation(), player.academicProgram(),
                player.semester(), player.teamName(), player.captain() ? "Sí" : "No", player.position(),
                player.jerseyNumber(), player.registrationStatus()};
        for (int column = 0; column < values.length; column++) {
            Object value = values[column];
            if (value == null) {
                continue;
            }
            Cell cell = row.createCell(column);
            if (value instanceof LocalDate date) {
                cell.setCellValue(date);
                cell.setCellStyle(dateStyle);
            } else if (value instanceof Integer number) {
                cell.setCellValue(number);
                widths[column] = Math.max(widths[column], String.valueOf(number).length());
            } else {
                String text = value.toString();
                cell.setCellValue(text);
                widths[column] = Math.max(widths[column], text.length());
            }
        }
    }
}
