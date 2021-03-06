/**
 * Copyright (c) 2016, All Contributors (see CONTRIBUTORS file)
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.eventsourcing;

import com.eventsourcing.layout.Layout;
import lombok.SneakyThrows;
import lombok.Value;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class StandardCommandTest {

    public static class SomeEvent extends StandardEvent {}

    @Value
    public static class SomeCommand extends StandardCommand<Void, Void> {
        String a;

        @Override public EventStream<Void> events() throws Exception {
            return EventStream.of(new SomeEvent());
        }
    }

    @Test
    @SneakyThrows
    public void layout() {
        Layout<SomeCommand> layout = Layout.forClass(SomeCommand.class);
        assertEquals(layout.getProperties().size(), 2);
        assertNotNull(layout.getProperty("a"));
        assertNotNull(layout.getProperty("timestamp"));
    }

    @Test
    @SneakyThrows
    public void passthrough() {
        EventStream<Void> eventStream = new SomeCommand("a").events(null, null);
        assertTrue(eventStream.getStream().anyMatch(e -> e instanceof SomeEvent));
    }

}