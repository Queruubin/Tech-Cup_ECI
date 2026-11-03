package edu.escuelaing.techcup.tournaments.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.tournaments.application.PlayerExportRow;
import edu.escuelaing.techcup.tournaments.application.PlayerExportSheet;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** The .xlsx is read back with POI: headers, bold frozen header row, real dates and numbers. */
class XlsxPlayerSpreadsheetWriterTest {

    private final XlsxPlayerSpreadsheetWriter writer = new XlsxPlayerSpreadsheetWriter();

    private static final PlayerExportRow ANA = new PlayerExportRow("Ana Díaz", "ana@escuelaing.edu.co",
            "+573001234567", "Cédula de ciudadanía", "1020", LocalDate.of(2004, 11, 2), 21, "Estudiante", "Ingeniería de Sistemas",
            6, "Tigers", true, "Delantero", 9, "Aprobada");
    private static final PlayerExportRow BETO = new PlayerExportRow("Beto Ruiz", "beto@mail.com", null, null, null,
            null, null, null, null, null, "Tigers", false, null, null, "Aprobada");

    @Test
    void writesTheHeadersBoldAndFrozenAndOneRowPerPlayer() throws IOException {
        try (XSSFWorkbook workbook = read(writer.write(List.of(
                new PlayerExportSheet("Aprobados", List.of(ANA, BETO)),
                new PlayerExportSheet("En revisión", List.of()))))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("Aprobados");
            assertThat(workbook.getSheetName(1)).isEqualTo("En revisión");

            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 15);
            for (int column = 0; column < XlsxPlayerSpreadsheetWriter.HEADERS.size(); column++) {
                Cell cell = header.getCell(column);
                assertThat(cell.getStringCellValue()).isEqualTo(XlsxPlayerSpreadsheetWriter.HEADERS.get(column));
                assertThat(workbook.getFontAt(cell.getCellStyle().getFontIndex()).getBold()).isTrue();
            }
            assertThat(XlsxPlayerSpreadsheetWriter.HEADERS).containsExactly("Nombre", "Correo", "Celular", "Tipo documento",
                    "Número documento", "Fecha de nacimiento", "Edad", "Relación con la Escuela", "Programa académico",
                    "Semestre", "Equipo", "Capitán", "Posición", "Dorsal", "Estado inscripción");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(sheet.getColumnWidth(0)).isGreaterThan(256 * "Nombre".length());

            Row ana = sheet.getRow(1);
            assertThat(ana.getCell(0).getStringCellValue()).isEqualTo("Ana Díaz");
            // The phone is a STRING cell, so Excel keeps the leading "+" and every digit.
            Cell phone = ana.getCell(2);
            assertThat(phone.getCellType()).isEqualTo(CellType.STRING);
            assertThat(phone.getStringCellValue()).isEqualTo("+573001234567");
            Cell birth = ana.getCell(5);
            assertThat(birth.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(birth)).isTrue();
            assertThat(birth.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2004, 11, 2));
            assertThat(birth.getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
            assertThat(ana.getCell(6).getNumericCellValue()).isEqualTo(21.0);
            assertThat(ana.getCell(9).getNumericCellValue()).isEqualTo(6.0);
            assertThat(ana.getCell(11).getStringCellValue()).isEqualTo("Sí");
            assertThat(ana.getCell(13).getNumericCellValue()).isEqualTo(9.0);
            assertThat(ana.getCell(14).getStringCellValue()).isEqualTo("Aprobada");

            Row beto = sheet.getRow(2);
            assertThat(beto.getCell(11).getStringCellValue()).isEqualTo("No");
            assertThat(beto.getCell(2)).isNull();
            assertThat(beto.getCell(5)).isNull();
            assertThat(beto.getCell(13)).isNull();

            Sheet empty = workbook.getSheetAt(1);
            assertThat(empty.getLastRowNum()).isZero();
            assertThat(empty.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Nombre");
        }
    }

    @Test
    void declaresTheOfficeOpenXmlMediaType() {
        assertThat(writer.contentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(writer.extension()).isEqualTo("xlsx");
    }

    private static XSSFWorkbook read(byte[] content) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(content));
    }
}
