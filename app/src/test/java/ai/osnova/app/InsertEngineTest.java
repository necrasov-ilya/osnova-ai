package ai.osnova.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InsertEngineTest {
    private final InsertEngine engine = new InsertEngine();

    @Test
    public void shouldSkipNearDuplicateBlock() {
        String current = "Производная показывает скорость изменения функции.\n" +
                "Правило суммы: производная суммы равна сумме производных.";
        String candidate = "Производная показывает скорость изменения функции\n" +
                "Правило суммы производная суммы равна сумме производных";

        assertFalse(engine.shouldInsert(current, candidate));
    }

    @Test
    public void shouldAllowNewTopicBlock() {
        String current = "Производная показывает скорость изменения функции.";
        String candidate = "Интеграл можно понимать как накопленную площадь под графиком функции.";

        assertTrue(engine.shouldInsert(current, candidate));
    }
}
