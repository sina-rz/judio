package io.github.sinaraz.gui;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {

    @Test
    void formatsMinutesAndSeconds() {
        assertEquals("00:00", TimeFormat.format(Duration.ZERO));
        assertEquals("01:05", TimeFormat.format(Duration.ofSeconds(65)));
        assertEquals("59:59", TimeFormat.format(Duration.ofSeconds(3599)));
    }

    @Test
    void addsHoursWhenNeeded() {
        assertEquals("1:02:05", TimeFormat.format(Duration.ofSeconds(3725)));
    }

    @Test
    void unknownOrNegativeDurations() {
        assertEquals("--:--", TimeFormat.format(null));
        assertEquals("00:00", TimeFormat.format(Duration.ofSeconds(-4)));
    }
}
