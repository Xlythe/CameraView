package com.xlythe.view.camera.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Undo and redo over snapshots, as Muse's ink history keeps them. */
public class HistoryTest {
    @Test
    public void undoAndRedoStepThroughCommits() {
        History<String> history = new History<>("a", 10);
        history.commit("b");
        history.commit("c");
        assertEquals("b", history.undo());
        assertEquals("a", history.undo());
        assertFalse(history.canUndo());
        assertEquals("a", history.undo());
        assertEquals("b", history.redo());
        assertEquals("c", history.redo());
        assertFalse(history.canRedo());
        assertEquals("c", history.redo());
    }

    @Test
    public void somethingNewAbandonsWhatCouldBeRedone() {
        History<String> history = new History<>("a", 10);
        history.commit("b");
        history.undo();
        history.commit("x");
        assertFalse(history.canRedo());
        history.undo();
        history.amend("y");
        assertFalse("an amend abandons it too", history.canRedo());
    }

    @Test
    public void aGestureIsOneStep() {
        History<String> history = new History<>("before", 10);
        history.commit("drag 1");
        history.amend("drag 2");
        history.amend("drag 3");
        assertEquals("before", history.undo());
    }

    @Test
    public void aPalmIsRolledBackWithNothingToRedo() {
        History<String> history = new History<>("before", 10);
        history.commit("palm");
        assertEquals("before", history.rollback());
        assertFalse(history.canRedo());
        assertEquals("before", history.rollback());
    }

    @Test
    public void theSameValueAgainIsNoStep() {
        String value = "same";
        History<String> history = new History<>(value, 10);
        history.commit(value);
        history.amend(value);
        assertFalse(history.canUndo());
    }

    @Test
    public void theOldestStepsFallOff() {
        History<Integer> history = new History<>(0, 2);
        history.commit(1);
        history.commit(2);
        history.commit(3);
        history.undo();
        history.undo();
        assertFalse(history.canUndo());
        assertEquals(1, history.current().intValue());
    }

    @Test
    public void aResetForgetsEverything() {
        History<String> history = new History<>("a", 10);
        history.commit("b");
        history.undo();
        history.reset("z");
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
        assertEquals("z", history.current());
        assertThrows(IllegalArgumentException.class, () -> new History<>("a", 0));
        assertTrue(new History<>("a", 1).current().equals("a"));
    }

    @Test
    public void eachChangeIsHeardButNotOnesThatChangeNothing() {
        History<String> history = new History<>("a", 2);
        List<Integer> events = new ArrayList<>();
        history.setListener(events::add);
        history.commit("b");
        history.amend("b2");
        history.commit("b2");
        history.undo();
        history.redo();
        history.commit("c");
        history.rollback();
        history.undo();
        history.undo();
        history.reset("z");
        history.rollback();
        assertEquals(Arrays.asList(History.EVENT_COMMIT, History.EVENT_AMEND,
                History.EVENT_UNDO, History.EVENT_REDO, History.EVENT_COMMIT,
                History.EVENT_ROLLBACK, History.EVENT_UNDO, History.EVENT_RESET), events);
        history.commit("1");
        history.commit("2");
        events.clear();
        history.commit("3");
        assertEquals("the oldest of two falls off",
                Arrays.asList(History.EVENT_DROP_OLDEST, History.EVENT_COMMIT), events);
    }
}
