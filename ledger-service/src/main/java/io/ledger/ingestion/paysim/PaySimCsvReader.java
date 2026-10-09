package io.ledger.ingestion.paysim;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/** Streams one partition of a PaySim file: header plus the lines before {@code startLine} are skipped. */
public class PaySimCsvReader implements ItemStreamReader<PaySimCsv> {

    private final Path file;
    private final long startLine;
    private final long lineCount;
    private BufferedReader reader;
    private long remaining;
    private long lineNumber;
    private long skipped;

    public PaySimCsvReader(Path file, long startLine, long lineCount) {
        this.file = file;
        this.startLine = startLine;
        this.lineCount = lineCount;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        try {
            reader = Files.newBufferedReader(file);
            if (reader.readLine() == null) {
                remaining = 0;
                return;
            }
            for (long i = 1; i < startLine; i++) {
                if (reader.readLine() == null) {
                    remaining = 0;
                    return;
                }
            }
            lineNumber = startLine - 1;
            remaining = lineCount;
            skipped = executionContext.getLong("skipped", 0);
        } catch (IOException e) {
            throw new ItemStreamException("Cannot read " + file, e);
        }
    }

    @Override
    public PaySimCsv read() throws IOException {
        while (remaining > 0) {
            String line = reader.readLine();
            remaining--;
            if (line == null) {
                return null;
            }
            lineNumber++;
            PaySimCsv row = PaySimCsv.parse(line, lineNumber);
            if (row == null) {
                continue;
            }
            if (row.skipped()) {
                skipped++;
                var step = PaySimTenantListener.CURRENT.get();
                if (step != null) {
                    ExecutionContext stepContext = step.getExecutionContext();
                    stepContext.putLong("skipped", stepContext.getLong("skipped", 0) + 1);
                }
            }
            return row;
        }
        return null;
    }

    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putLong("skipped", Math.max(skipped, executionContext.getLong("skipped", 0)));
    }

    @Override
    public void close() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                throw new ItemStreamException("Cannot close " + file, e);
            }
        }
    }
}
