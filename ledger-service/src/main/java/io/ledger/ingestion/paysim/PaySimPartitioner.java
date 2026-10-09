package io.ledger.ingestion.paysim;

import java.util.HashMap;
import java.util.Map;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/** Splits the data lines of one CSV into contiguous ranges, one per worker. */
public class PaySimPartitioner implements Partitioner {

    private final String file;
    private final long lineCount;

    public PaySimPartitioner(String file, long lineCount) {
        this.file = file;
        this.lineCount = lineCount;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new HashMap<>();
        if (lineCount <= 0 || gridSize < 1) {
            return partitions;
        }
        int parts = (int) Math.min(gridSize, lineCount);
        long size = (lineCount + parts - 1) / parts;
        for (int i = 0; i < parts; i++) {
            long start = i * size + 1;
            if (start > lineCount) {
                break;
            }
            long count = Math.min(size, lineCount - start + 1);
            ExecutionContext context = new ExecutionContext();
            context.putString("file", file);
            context.putLong("startLine", start);
            context.putLong("lineCount", count);
            partitions.put("partition" + i, context);
        }
        return partitions;
    }
}
