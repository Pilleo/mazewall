package consumer.spring;

import io.mazewall.spring.MazewallOperation;
import org.springframework.transaction.annotation.Transactional;

/** Compiled into a separate actual ordinary JAR, never an included implementation owner. */
public final class ForeignJdbcOperation implements NativeJdbcConsumer.Operation {
    @Override @MazewallOperation("jdbc-write") @Transactional(transactionManager = "jdbc")
    public String run(String mode) { throw new AssertionError("Uncaptured foreign origin must never execute"); }
}
