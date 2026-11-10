package com.coke.nestspace.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PluginSpeechAudioTest {

    @Test
    public void calculatesPcmDuration() {
        PluginSpeechAudio audio = new PluginSpeechAudio(
            new byte[24_000 * 2],
            24_000,
            1
        );

        assertEquals(1_000L, audio.getDurationMs());
    }

    @Test
    public void rejectsMisalignedStereoFrames() {
        assertThrows(IllegalArgumentException.class, () ->
            new PluginSpeechAudio(new byte[6], 24_000, 2)
        );
    }

    @Test
    public void rejectsUnsupportedSampleRate() {
        assertThrows(IllegalArgumentException.class, () ->
            new PluginSpeechAudio(new byte[4], 4_000, 1)
        );
    }
}
