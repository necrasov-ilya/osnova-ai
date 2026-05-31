package ai.osnova.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DraftBuilderTest {
    @Test
    public void buildRejectsOcrNoise() {
        DraftCandidate draft = DraftBuilder.INSTANCE.build("d o - od\n=>\n| ~ ~~\ng ddy lu do aau god", "test");

        assertNull(draft);
    }

    @Test
    public void buildKeepsReadableRussianLines() {
        DraftCandidate draft = DraftBuilder.INSTANCE.build(
                "Производная показывает скорость изменения функции\n" +
                        "Правило суммы: производная суммы равна сумме производных",
                "test"
        );

        assertNotNull(draft);
        assertTrue(draft.getText().contains("Производная"));
        assertFalse(draft.getText().contains("=>"));
    }
}
