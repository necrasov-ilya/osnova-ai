package ai.osnova.app;

import static org.junit.Assert.assertEquals;

import java.util.Collections;

import org.junit.Test;

public class NoteBlockEngineTest {
    @Test
    public void updateMergesStreamingMarkdownIntoGeneratingBlock() {
        Note note = new Note(
                1L,
                "Пара",
                "",
                Collections.emptyList(),
                1L,
                1L
        );

        Note withBlock = NoteBlockEngine.INSTANCE.appendGenerating(note, 10L, "сырой текст", "");
        Note updated = NoteBlockEngine.INSTANCE.update(
                withBlock,
                10L,
                "## Производная\n- Скорость изменения функции",
                NoteBlockStatus.Generating
        );

        assertEquals(1, updated.getBlocks().size());
        assertEquals(NoteBlockStatus.Generating, updated.getBlocks().get(0).getStatus());
        assertEquals("## Производная\n- Скорость изменения функции", updated.getBody());
    }
}
